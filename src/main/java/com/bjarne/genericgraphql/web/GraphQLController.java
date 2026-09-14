package com.bjarne.genericgraphql.web;

import com.bjarne.genericgraphql.engine.dataloader.RelationDataLoaders;
import com.bjarne.genericgraphql.engine.query.RequestState;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import java.util.LinkedHashMap;
import java.util.Map;
import org.dataloader.DataLoaderRegistry;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/graphql")
public class GraphQLController {

    private final GraphQL graphQL;
    private final RelationDataLoaders dataLoaders;

    public GraphQLController(GraphQL graphQL, RelationDataLoaders dataLoaders) {
        this.graphQL = graphQL;
        this.dataLoaders = dataLoaders;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public Map<String, Object> execute(@RequestBody GraphQLRequest request) {
        RequestState state = new RequestState();
        DataLoaderRegistry registry = dataLoaders.newRegistry(state);

        Map<String, Object> contextValues = new LinkedHashMap<>();
        contextValues.put(RelationDataLoaders.CONTEXT_KEY, registry);
        contextValues.put(RequestState.CONTEXT_KEY, state);

        ExecutionInput input = ExecutionInput.newExecutionInput()
                .query(request.query())
                .operationName(request.operationName())
                .variables(request.variables() == null ? Map.of() : request.variables())
                .dataLoaderRegistry(registry)
                .graphQLContext(contextValues)
                .build();

        ExecutionResult result = graphQL.execute(input);
        return result.toSpecification();
    }

    public record GraphQLRequest(String query, String operationName, Map<String, Object> variables) {
    }
}
