package com.bjarne.genericgraphql.engine.filter;

import com.bjarne.genericgraphql.engine.types.LookupTypes;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import java.util.List;
import java.util.Map;

public final class LookupPredicates {

    private LookupPredicates() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Predicate build(CriteriaBuilder cb, Path<?> field, Map<String, Object> lookup) {
        Predicate result = cb.conjunction();

        for (Map.Entry<String, Object> entry : lookup.entrySet()) {
            Object value = entry.getValue();
            if (value == null && !LookupTypes.IS_NULL.equals(entry.getKey())) {
                continue;
            }

            Predicate predicate = switch (entry.getKey()) {
                case LookupTypes.EXACT -> cb.equal(field, value);
                case LookupTypes.I_EXACT -> cb.equal(lower(cb, field), lower(value));
                case LookupTypes.CONTAINS -> cb.like(asString(field), "%" + escape(value) + "%", '\\');
                case LookupTypes.I_CONTAINS -> cb.like(lower(cb, field), "%" + lower(escape(value)) + "%", '\\');
                case LookupTypes.STARTS_WITH -> cb.like(asString(field), escape(value) + "%", '\\');
                case LookupTypes.I_STARTS_WITH -> cb.like(lower(cb, field), lower(escape(value)) + "%", '\\');
                case LookupTypes.ENDS_WITH -> cb.like(asString(field), "%" + escape(value), '\\');
                case LookupTypes.I_ENDS_WITH -> cb.like(lower(cb, field), "%" + lower(escape(value)), '\\');
                case LookupTypes.IN_LIST -> field.in((List<?>) value);
                case LookupTypes.IS_NULL -> Boolean.TRUE.equals(value) ? cb.isNull(field) : cb.isNotNull(field);
                case LookupTypes.GT -> cb.greaterThan((Expression) field, (Comparable) value);
                case LookupTypes.GTE -> cb.greaterThanOrEqualTo((Expression) field, (Comparable) value);
                case LookupTypes.GT_OR_NULL ->
                        cb.or(cb.greaterThan((Expression) field, (Comparable) value), cb.isNull(field));
                case LookupTypes.LT -> cb.lessThan((Expression) field, (Comparable) value);
                case LookupTypes.LTE -> cb.lessThanOrEqualTo((Expression) field, (Comparable) value);
                case LookupTypes.LT_OR_NULL ->
                        cb.or(cb.lessThan((Expression) field, (Comparable) value), cb.isNull(field));
                case LookupTypes.RANGE -> range(cb, field, (List<?>) value);
                case LookupTypes.IS_TRUE -> Boolean.TRUE.equals(value) ? cb.isTrue(asBoolean(field)) : cb.conjunction();
                case LookupTypes.IS_FALSE -> Boolean.TRUE.equals(value) ? cb.isFalse(asBoolean(field)) : cb.conjunction();
                case LookupTypes.IS_TRUE_OR_NULL -> Boolean.TRUE.equals(value)
                        ? cb.or(cb.isTrue(asBoolean(field)), cb.isNull(field))
                        : cb.conjunction();
                case LookupTypes.IS_FALSE_OR_NULL -> Boolean.TRUE.equals(value)
                        ? cb.or(cb.isFalse(asBoolean(field)), cb.isNull(field))
                        : cb.conjunction();
                default -> throw new IllegalArgumentException("Unbekannter Lookup: " + entry.getKey());
            };

            result = cb.and(result, predicate);
        }

        return result;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Predicate range(CriteriaBuilder cb, Path<?> field, List<?> bounds) {
        if (bounds.size() != 2) {
            throw new IllegalArgumentException("range erwartet genau zwei Werte [von, bis]");
        }
        return cb.between((Expression) field, (Comparable) bounds.get(0), (Comparable) bounds.get(1));
    }

    @SuppressWarnings("unchecked")
    private static Expression<String> asString(Path<?> field) {
        return (Expression<String>) field;
    }

    @SuppressWarnings("unchecked")
    private static Expression<Boolean> asBoolean(Path<?> field) {
        return (Expression<Boolean>) field;
    }

    private static Expression<String> lower(CriteriaBuilder cb, Path<?> field) {
        return cb.lower(asString(field));
    }

    private static String lower(Object value) {
        return String.valueOf(value).toLowerCase();
    }

    private static String escape(Object value) {
        return String.valueOf(value)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
