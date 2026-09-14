package com.bjarne.genericgraphql.engine.scope;

import com.bjarne.genericgraphql.engine.meta.EntityMeta;
import graphql.GraphQLContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Predicate;

public interface AccessPolicy {

    Predicate visibilityPredicate(CriteriaBuilder cb,
                                  From<?, ?> root,
                                  EntityMeta meta,
                                  GraphQLContext context);

    static AccessPolicy permitAll() {
        return (cb, root, meta, context) -> cb.conjunction();
    }
}
