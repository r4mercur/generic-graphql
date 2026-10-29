package com.bjarne.genericgraphql.remote;

import com.bjarne.genericgraphql.engine.types.LookupTypes;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Wertet die Filtersprache der generischen Engine auf Records im Speicher aus - dieselben
 * Lookups, dasselbe AND/OR/NOT. Steht in der Simulation fuer die generische Engine, die ein
 * echter Owner selbst betreiben wuerde.
 *
 * <p>Der Owner prueft die Freigabe selbst: Ein Feld ausserhalb von {@code filterable} wird
 * abgelehnt, auch wenn die Orchestrierung es durchlassen wuerde.
 */
public final class InMemoryFilter {

    private InMemoryFilter() {
    }

    public static boolean matches(Object record, Map<String, Object> filter, Set<String> filterable) {
        return evaluate(record, filter, false, filterable);
    }

    @SuppressWarnings("unchecked")
    private static boolean evaluate(Object record, Map<String, Object> filter, boolean linkOr, Set<String> filterable) {
        boolean result = !linkOr;
        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            Map<String, Object> value = (Map<String, Object>) entry.getValue();
            boolean match = switch (entry.getKey()) {
                case LookupTypes.AND -> evaluate(record, value, false, filterable);
                case LookupTypes.OR -> evaluate(record, value, true, filterable);
                case LookupTypes.NOT -> !evaluate(record, value, false, filterable);
                default -> {
                    if (!filterable.contains(entry.getKey())) {
                        throw new IllegalArgumentException("Feld " + entry.getKey() + " ist nicht zum Filtern freigegeben");
                    }
                    yield lookup(read(record, entry.getKey()), value);
                }
            };
            result = linkOr ? result || match : result && match;
        }
        return result;
    }

    private static boolean lookup(Object actual, Map<String, Object> lookup) {
        for (Map.Entry<String, Object> entry : lookup.entrySet()) {
            Object expected = entry.getValue();
            if (expected == null && !LookupTypes.IS_NULL.equals(entry.getKey())) {
                continue;
            }
            if (!lookupMatches(entry.getKey(), actual, expected)) {
                return false;
            }
        }
        return true;
    }

    private static boolean lookupMatches(String lookup, Object actual, Object expected) {
        if (LookupTypes.IS_NULL.equals(lookup)) {
            return (actual == null) == Boolean.TRUE.equals(expected);
        }
        if (actual == null) {
            return lookup.endsWith("OrNull");
        }
        String text = String.valueOf(actual);
        String needle = String.valueOf(expected);
        return switch (lookup) {
            case LookupTypes.EXACT -> compare(actual, expected) == 0;
            case LookupTypes.I_EXACT -> text.equalsIgnoreCase(needle);
            case LookupTypes.CONTAINS -> text.contains(needle);
            case LookupTypes.I_CONTAINS -> text.toLowerCase().contains(needle.toLowerCase());
            case LookupTypes.STARTS_WITH -> text.startsWith(needle);
            case LookupTypes.I_STARTS_WITH -> text.toLowerCase().startsWith(needle.toLowerCase());
            case LookupTypes.ENDS_WITH -> text.endsWith(needle);
            case LookupTypes.I_ENDS_WITH -> text.toLowerCase().endsWith(needle.toLowerCase());
            case LookupTypes.IN_LIST -> ((List<?>) expected).stream().anyMatch(item -> compare(actual, item) == 0);
            case LookupTypes.GT, LookupTypes.GT_OR_NULL -> compare(actual, expected) > 0;
            case LookupTypes.GTE -> compare(actual, expected) >= 0;
            case LookupTypes.LT, LookupTypes.LT_OR_NULL -> compare(actual, expected) < 0;
            case LookupTypes.LTE -> compare(actual, expected) <= 0;
            case LookupTypes.RANGE -> compare(actual, ((List<?>) expected).get(0)) >= 0
                    && compare(actual, ((List<?>) expected).get(1)) <= 0;
            case LookupTypes.IS_TRUE, LookupTypes.IS_TRUE_OR_NULL ->
                    !Boolean.TRUE.equals(expected) || Boolean.TRUE.equals(actual);
            case LookupTypes.IS_FALSE, LookupTypes.IS_FALSE_OR_NULL ->
                    !Boolean.TRUE.equals(expected) || Boolean.FALSE.equals(actual);
            default -> throw new IllegalArgumentException("Unbekannter Lookup: " + lookup);
        };
    }

    // BigDecimal("0.70") und BigDecimal("0.7") sind nicht equals, aber gleich gross.
    @SuppressWarnings("unchecked")
    private static int compare(Object actual, Object expected) {
        if (actual instanceof Comparable comparable && expected != null && actual.getClass() == expected.getClass()) {
            return comparable.compareTo(expected);
        }
        return Objects.equals(actual, expected) ? 0 : -1;
    }

    private static Object read(Object record, String component) {
        try {
            return record.getClass().getMethod(component).invoke(record);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalArgumentException(record.getClass().getSimpleName() + " hat kein Feld " + component, ex);
        }
    }
}
