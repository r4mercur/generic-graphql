package com.bjarne.genericgraphql.inspector;

import com.bjarne.genericgraphql.engine.probe.EngineProbes;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.execution.instrumentation.Instrumentation;
import graphql.execution.instrumentation.InstrumentationContext;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.SimpleInstrumentationContext;
import graphql.execution.instrumentation.parameters.InstrumentationCreateStateParameters;
import graphql.execution.instrumentation.parameters.InstrumentationExecuteOperationParameters;
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters;
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLTypeUtil;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.dataloader.DataLoaderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Der Mitschnitt als zweite graphql-java-Instrumentation hinter den Guardrails: ein
 * {@link TraceCollector} je Request, an den Request-Thread gebunden, damit die Engine ueber
 * {@code EngineProbe.current()} melden kann. Resolver mit eigener Logik werden gemessen,
 * einfache Property-Zugriffe nicht. Introspection (GraphiQL) wird nicht veroeffentlicht.
 */
@Component
@ConditionalOnProperty(name = "inspector.enabled", havingValue = "true", matchIfMissing = true)
public class TraceInstrumentation implements Instrumentation {

    private static final Logger log = LoggerFactory.getLogger(TraceInstrumentation.class);

    private final TraceHub hub;
    private final FieldClassifier classifier;

    public TraceInstrumentation(TraceHub hub, FieldClassifier classifier) {
        this.hub = hub;
        this.classifier = classifier;
    }

    @Override
    public InstrumentationState createState(InstrumentationCreateStateParameters parameters) {
        ExecutionInput input = parameters.getExecutionInput();
        return new TraceCollector(input.getQuery(), input.getOperationName(), loaders(input.getDataLoaderRegistry()));
    }

    @Override
    public ExecutionInput instrumentExecutionInput(ExecutionInput input,
                                                   InstrumentationExecutionParameters parameters,
                                                   InstrumentationState state) {
        if (state instanceof TraceCollector trace) {
            EngineProbes.bind(trace);
        }
        return input;
    }

    @Override
    public InstrumentationContext<ExecutionResult> beginExecuteOperation(InstrumentationExecuteOperationParameters parameters,
                                                                         InstrumentationState state) {
        if (state instanceof TraceCollector trace) {
            trace.operation(parameters.getExecutionContext().getOperationDefinition());
            if (!trace.introspection()) {
                hub.started(trace);
            }
        }
        return SimpleInstrumentationContext.noOp();
    }

    @Override
    public DataFetcher<?> instrumentDataFetcher(DataFetcher<?> dataFetcher,
                                                InstrumentationFieldFetchParameters parameters,
                                                InstrumentationState state) {
        if (!(state instanceof TraceCollector trace) || parameters.isTrivialDataFetcher() || trace.introspection()) {
            return dataFetcher;
        }
        return (DataFetchingEnvironment env) -> {
            String field = env.getField().getName();
            if (field.startsWith("__")) {
                return dataFetcher.get(env);
            }
            FieldClassifier.Kind kind = classifier.classify(GraphQLTypeUtil.simplePrint(env.getParentType()), field);
            return trace.fetch(dataFetcher, env, kind);
        };
    }

    @Override
    public CompletableFuture<ExecutionResult> instrumentExecutionResult(ExecutionResult executionResult,
                                                                       InstrumentationExecutionParameters parameters,
                                                                       InstrumentationState state) {
        if (state instanceof TraceCollector trace) {
            try {
                if (!trace.introspection()) {
                    hub.finished(trace, trace.finish(executionResult));
                }
            } catch (RuntimeException ex) {
                log.warn("Inspector: Mitschnitt konnte nicht abgeschlossen werden", ex);
            } finally {
                if (EngineProbes.current() == trace) {
                    EngineProbes.restore(null);
                }
            }
        }
        return CompletableFuture.completedFuture(executionResult);
    }

    /** "13 je Request: 4 forward \u00b7 7 reverse \u00b7 2 remote" */
    private static String loaders(DataLoaderRegistry registry) {
        if (registry == null) {
            return null;
        }
        Set<String> keys = registry.getKeys();
        long forward = keys.stream().filter(key -> key.startsWith("forward:")).count();
        long reverse = keys.stream().filter(key -> key.startsWith("reverse:")).count();
        long remote = keys.stream().filter(key -> key.startsWith("remote:")).count();
        return keys.size() + " je Request: " + forward + " forward \u00b7 " + reverse + " reverse \u00b7 " + remote + " remote";
    }
}
