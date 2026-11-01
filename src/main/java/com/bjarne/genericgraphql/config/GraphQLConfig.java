package com.bjarne.genericgraphql.config;

import com.bjarne.genericgraphql.engine.SchemaFactory;
import graphql.GraphQL;
import graphql.execution.instrumentation.ChainedInstrumentation;
import graphql.execution.instrumentation.Instrumentation;
import graphql.schema.GraphQLSchema;
 import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GraphQLConfig {

    @Bean
    public GraphQLSchema graphQLSchema(SchemaFactory schemaFactory) {
        return schemaFactory.build();
    }

    @Bean
    public GraphQL graphQL(GraphQLSchema schema,
                           @Value("${gql.max-depth:12}") int maxDepth,
                           @Value("${gql.max-complexity:2000}") int maxComplexity,
                           ObjectProvider<Instrumentation> additional) {

        List<Instrumentation> chain = new ArrayList<>(List.of(
                Guardrails.maxDepth(maxDepth),
                Guardrails.maxComplexity(maxComplexity)));
        // Weitere Instrumentations als Beans (z. B. der Mitschnitt des Inspectors) laufen nach den Guardrails.
        additional.orderedStream().forEach(chain::add);

        return GraphQL.newGraphQL(schema)
                .instrumentation(new ChainedInstrumentation(chain))
                .build();
    }
}
