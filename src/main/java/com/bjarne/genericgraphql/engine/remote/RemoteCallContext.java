package com.bjarne.genericgraphql.engine.remote;

import graphql.GraphQLContext;
import java.time.LocalDate;

/**
 * Was die Engine einem fremden Service mitgeben kann: den Stichtag der Query und den
 * GraphQL-Kontext (z. B. fuer die Weitergabe eines Tokens). Ob der Service den Stichtag
 * auswerten kann, entscheidet er selbst.
 */
public record RemoteCallContext(LocalDate date, GraphQLContext graphQLContext) {
}
