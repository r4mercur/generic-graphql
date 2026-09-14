package com.bjarne.genericgraphql.engine.filter;

import com.bjarne.genericgraphql.engine.meta.EntityMeta;
import com.bjarne.genericgraphql.engine.meta.MetaProvider;
import com.bjarne.genericgraphql.engine.scope.AccessPolicy;
import com.bjarne.genericgraphql.engine.scope.LatestVersionScope;
import com.bjarne.genericgraphql.engine.types.LookupTypes;
import graphql.GraphQLContext;
import graphql.schema.DataFetchingFieldSelectionSet;
import graphql.schema.SelectedField;
import jakarta.persistence.criteria.AbstractQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class FilterEngine {

    private final MetaProvider metaProvider;
    private final LatestVersionScope latestVersionScope;
    private final AccessPolicy accessPolicy;

    public FilterEngine(MetaProvider metaProvider,
                        LatestVersionScope latestVersionScope,
                        AccessPolicy accessPolicy) {
        this.metaProvider = metaProvider;
        this.latestVersionScope = latestVersionScope;
        this.accessPolicy = accessPolicy;
    }

    public record FilterContext(LocalDate date,
                                DataFetchingFieldSelectionSet selectionSet,
                                GraphQLContext graphQLContext) {
    }

    public Predicate build(CriteriaBuilder cb,
                           AbstractQuery<?> query,
                           From<?, ?> path,
                           EntityMeta meta,
                           Map<String, Object> filter,
                           boolean linkOr,
                           FilterContext context) {

        Predicate result = linkOr ? cb.disjunction() : cb.conjunction();
        if (filter == null || filter.isEmpty()) {
            return result;
        }

        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            String fieldName = entry.getKey();
            Object rawValue = entry.getValue();
            if (rawValue == null) {
                continue;
            }

            Predicate predicate = switch (fieldName) {
                case LookupTypes.AND -> build(cb, query, path, meta, asMap(rawValue), false, context);
                case LookupTypes.OR -> build(cb, query, path, meta, asMap(rawValue), true, context);
                case LookupTypes.NOT -> cb.not(build(cb, query, path, meta, asMap(rawValue), false, context));
                case LookupTypes.WILDCARD -> buildWildcard(cb, query, path, meta, asMap(rawValue),
                        context.selectionSet(), context);
                default -> buildField(cb, query, path, meta, fieldName, asMap(rawValue), context);
            };

            result = combine(cb, result, predicate, linkOr);
        }

        return result;
    }

    private Predicate buildField(CriteriaBuilder cb,
                                 AbstractQuery<?> query,
                                 From<?, ?> path,
                                 EntityMeta meta,
                                 String fieldName,
                                 Map<String, Object> value,
                                 FilterContext context) {

        EntityMeta.BasicAttr basic = meta.basic(fieldName);
        if (basic != null) {
            return LookupPredicates.build(cb, path.get(fieldName), value);
        }

        EntityMeta.ForwardRel forward = meta.forward(fieldName);
        if (forward != null) {
            return forwardSubquery(cb, query, path, forward, value, context);
        }

        EntityMeta.ReverseRel reverse = meta.reverse(fieldName);
        if (reverse != null) {
            return reverseSubquery(cb, query, path, reverse, value, context);
        }

        throw new IllegalArgumentException(
                "Unbekanntes Filterfeld '" + fieldName + "' fuer " + meta.entityClass().getSimpleName());
    }

    // -------------------------------------------------------------------------
    // Relations-Subqueries
    // -------------------------------------------------------------------------

    private Predicate forwardSubquery(CriteriaBuilder cb,
                                      AbstractQuery<?> outerQuery,
                                      From<?, ?> path,
                                      EntityMeta.ForwardRel rel,
                                      Map<String, Object> subFilter,
                                      FilterContext context) {

        EntityMeta targetMeta = metaProvider.meta(rel.targetClass());
        Subquery<Object> sub = outerQuery.subquery(Object.class);
        @SuppressWarnings("unchecked")
        Root<Object> subRoot = (Root<Object>) sub.from(rel.targetClass());
        sub.select(subRoot.get(targetMeta.pkAttribute()));

        Predicate correlation = cb.equal(
                path.get(rel.name()).get(rel.targetPkAttribute()),
                subRoot.get(rel.targetIdAttribute()));

        sub.where(cb.and(
                scope(cb, sub, subRoot, targetMeta, context),
                correlation,
                build(cb, sub, subRoot, targetMeta, subFilter, false, context)));

        return cb.exists(sub);
    }

    private Predicate reverseSubquery(CriteriaBuilder cb,
                                      AbstractQuery<?> outerQuery,
                                      From<?, ?> path,
                                      EntityMeta.ReverseRel rel,
                                      Map<String, Object> subFilter,
                                      FilterContext context) {

        EntityMeta targetMeta = metaProvider.meta(rel.targetClass());
        EntityMeta.ForwardRel backRef = targetMeta.forward(rel.mappedBy());
        if (backRef == null) {
            throw new IllegalStateException("Gegenrichtung " + rel.mappedBy() + " von "
                    + rel.targetClass().getSimpleName() + " ist nicht registriert");
        }

        Subquery<Object> sub = outerQuery.subquery(Object.class);
        @SuppressWarnings("unchecked")
        Root<Object> subRoot = (Root<Object>) sub.from(rel.targetClass());
        sub.select(subRoot.get(targetMeta.pkAttribute()));

        Predicate correlation = cb.equal(
                subRoot.get(rel.mappedBy()).get(backRef.targetPkAttribute()),
                path.get(rel.ownIdAttribute()));

        sub.where(cb.and(
                scope(cb, sub, subRoot, targetMeta, context),
                correlation,
                build(cb, sub, subRoot, targetMeta, subFilter, false, context)));

        return cb.exists(sub);
    }

    public Predicate scope(CriteriaBuilder cb,
                           AbstractQuery<?> query,
                           From<?, ?> root,
                           EntityMeta meta,
                           FilterContext context) {
        Predicate visibility = accessPolicy.visibilityPredicate(cb, root, meta, context.graphQLContext());
        if (!meta.registration().eventSourced()) {
            return visibility;
        }
        return cb.and(visibility, latestVersionScope.scopeToLatest(cb, query, root, meta, context.date()));
    }

    // -------------------------------------------------------------------------
    // Wildcard-Filter
    // -------------------------------------------------------------------------

    private Predicate buildWildcard(CriteriaBuilder cb,
                                    AbstractQuery<?> query,
                                    From<?, ?> path,
                                    EntityMeta meta,
                                    Map<String, Object> wildcardLookup,
                                    DataFetchingFieldSelectionSet selectionSet,
                                    FilterContext context) {

        if (wildcardLookup.isEmpty()) {
            throw new IllegalArgumentException("Leerer " + LookupTypes.WILDCARD + " ist nicht auswertbar");
        }
        if (selectionSet == null) {
            return cb.disjunction();
        }

        List<Predicate> alternatives = new ArrayList<>();
        collectWildcard(cb, query, path, meta, wildcardLookup, immediateFields(selectionSet), context, alternatives, 0);

        return alternatives.isEmpty() ? cb.disjunction() : cb.or(alternatives.toArray(new Predicate[0]));
    }

    private void collectWildcard(CriteriaBuilder cb,
                                 AbstractQuery<?> query,
                                 From<?, ?> path,
                                 EntityMeta meta,
                                 Map<String, Object> lookup,
                                 List<SelectedField> selected,
                                 FilterContext context,
                                 List<Predicate> out,
                                 int depth) {

        if (depth > 3) {
            return;
        }

        for (SelectedField field : selected) {
            String name = field.getName();

            EntityMeta.BasicAttr basic = meta.basic(name);
            if (basic != null && basic.isString()) {
                out.add(LookupPredicates.build(cb, path.get(name), lookup));
                continue;
            }

            EntityMeta.ForwardRel forward = meta.forward(name);
            if (forward != null) {
                EntityMeta targetMeta = metaProvider.meta(forward.targetClass());
                List<Predicate> nested = new ArrayList<>();
                Subquery<Object> sub = query.subquery(Object.class);
                @SuppressWarnings("unchecked")
                Root<Object> subRoot = (Root<Object>) sub.from(forward.targetClass());
                sub.select(subRoot.get(targetMeta.pkAttribute()));

                collectWildcard(cb, sub, subRoot, targetMeta, lookup,
                        immediateFields(field.getSelectionSet()), context, nested, depth + 1);
                if (nested.isEmpty()) {
                    continue;
                }
                sub.where(cb.and(
                        scope(cb, sub, subRoot, targetMeta, context),
                        cb.equal(path.get(forward.name()).get(forward.targetPkAttribute()),
                                subRoot.get(forward.targetIdAttribute())),
                        cb.or(nested.toArray(new Predicate[0]))));
                out.add(cb.exists(sub));
                continue;
            }

            EntityMeta.ReverseRel reverse = meta.reverse(name);
            if (reverse != null) {
                EntityMeta targetMeta = metaProvider.meta(reverse.targetClass());
                EntityMeta.ForwardRel backRef = targetMeta.forward(reverse.mappedBy());
                if (backRef == null) {
                    continue;
                }
                List<Predicate> nested = new ArrayList<>();
                Subquery<Object> sub = query.subquery(Object.class);
                @SuppressWarnings("unchecked")
                Root<Object> subRoot = (Root<Object>) sub.from(reverse.targetClass());
                sub.select(subRoot.get(targetMeta.pkAttribute()));

                collectWildcard(cb, sub, subRoot, targetMeta, lookup,
                        immediateFields(field.getSelectionSet()), context, nested, depth + 1);
                if (nested.isEmpty()) {
                    continue;
                }
                sub.where(cb.and(
                        scope(cb, sub, subRoot, targetMeta, context),
                        cb.equal(subRoot.get(reverse.mappedBy()).get(backRef.targetPkAttribute()),
                                path.get(reverse.ownIdAttribute())),
                        cb.or(nested.toArray(new Predicate[0]))));
                out.add(cb.exists(sub));
            }
        }
    }

    private static List<SelectedField> immediateFields(DataFetchingFieldSelectionSet selectionSet) {
        return selectionSet == null ? List.of() : selectionSet.getImmediateFields();
    }

    // -------------------------------------------------------------------------
    // Helper-Methods
    // -------------------------------------------------------------------------

    private static Predicate combine(CriteriaBuilder cb, Predicate acc, Predicate next, boolean linkOr) {
        return linkOr ? cb.or(acc, next) : cb.and(acc, next);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return new LinkedHashMap<>();
    }

    public Predicate buildRoot(CriteriaBuilder cb,
                               AbstractQuery<?> query,
                               From<?, ?> root,
                               EntityMeta meta,
                               Map<String, Object> filter,
                               FilterContext context) {
        return build(cb, query, root, meta, filter, false, context);
    }

    public Path<?> resolvePath(From<?, ?> root, String attribute) {
        return root.get(attribute);
    }
}
