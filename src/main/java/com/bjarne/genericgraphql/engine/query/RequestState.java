package com.bjarne.genericgraphql.engine.query;

import graphql.GraphQLContext;
import java.time.LocalDate;

public final class RequestState {

    public static final String CONTEXT_KEY = "gql.request";

    private volatile LocalDate date;
    private volatile GraphQLContext graphQLContext;

    public LocalDate date() {
        return date != null ? date : LocalDate.now();
    }

    public void dateIfAbsent(LocalDate value) {
        if (value != null && date == null) {
            this.date = value;
        }
    }

    public GraphQLContext graphQLContext() {
        return graphQLContext;
    }

    public void graphQLContext(GraphQLContext context) {
        this.graphQLContext = context;
    }
}
