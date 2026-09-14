package com.bjarne.genericgraphql.config;

import graphql.ExecutionResult;
import graphql.analysis.MaxQueryComplexityInstrumentation;
import graphql.analysis.MaxQueryDepthInstrumentation;
import graphql.execution.instrumentation.InstrumentationContext;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.SimpleInstrumentationContext;
import graphql.execution.instrumentation.parameters.InstrumentationExecuteOperationParameters;
import graphql.language.Field;
import graphql.language.Selection;

final class Guardrails {

    private Guardrails() {
    }

    static MaxQueryDepthInstrumentation maxDepth(int maxDepth) {
        return new MaxQueryDepthInstrumentation(maxDepth) {
            @Override
            public InstrumentationContext<ExecutionResult> beginExecuteOperation(
                    InstrumentationExecuteOperationParameters parameters, InstrumentationState state) {
                if (isIntrospectionOnly(parameters)) {
                    return SimpleInstrumentationContext.noOp();
                }
                return super.beginExecuteOperation(parameters, state);
            }
        };
    }

    static MaxQueryComplexityInstrumentation maxComplexity(int maxComplexity) {
        return new MaxQueryComplexityInstrumentation(maxComplexity) {
            @Override
            public InstrumentationContext<ExecutionResult> beginExecuteOperation(
                    InstrumentationExecuteOperationParameters parameters, InstrumentationState state) {
                if (isIntrospectionOnly(parameters)) {
                    return SimpleInstrumentationContext.noOp();
                }
                return super.beginExecuteOperation(parameters, state);
            }
        };
    }

    private static boolean isIntrospectionOnly(InstrumentationExecuteOperationParameters parameters) {
        var operation = parameters.getExecutionContext().getOperationDefinition();
        if (operation == null || operation.getSelectionSet() == null) {
            return false;
        }
        var selections = operation.getSelectionSet().getSelections();
        if (selections.isEmpty()) {
            return false;
        }
        for (Selection<?> selection : selections) {
            if (!(selection instanceof Field field) || !field.getName().startsWith("__")) {
                return false;
            }
        }
        return true;
    }
}
