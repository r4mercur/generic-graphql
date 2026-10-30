package com.bjarne.genericgraphql.engine.remote;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Die Teilbaeume eines Filters, die einem anderen Service gehoeren - und, sobald aufgeloest,
 * dessen Treffer.
 *
 * <p>Die Filter-Engine laeuft fuer einen Filter mit Remote-Feldern zweimal ueber denselben
 * Code: Im <b>Planungslauf</b> meldet jeder fremde Teilbaum sich hier an und bekommt eine
 * leere Menge. {@link RemoteFilterSearch} fragt dann alle Owner parallel. Im
 * <b>Ausfuehrungslauf</b> liefert {@link #ids} die Treffer, und die Filter-Engine setzt sie
 * als {@code IN} an genau der Stelle ein, an der der Teilbaum stand.
 */
public final class RemoteFilterPlan {

    /** Ein Teilbaum fuer einen Owner. Gleiche Teilbaeume werden nur einmal angefragt. */
    public record Request(RemoteField<?> field, Map<String, Object> filter) {
    }

    private final Set<Request> requests = new LinkedHashSet<>();
    private final Map<Request, Set<String>> results = new HashMap<>();
    private boolean resolved;

    public Set<String> ids(RemoteField<?> field, Map<String, Object> filter) {
        Request request = new Request(field, new LinkedHashMap<>(filter));
        if (!resolved) {
            requests.add(request);
            return Set.of();
        }
        Set<String> ids = results.get(request);
        if (ids == null) {
            throw new IllegalStateException("Filter auf " + field.name() + " wurde nicht geplant");
        }
        return ids;
    }

    public boolean isEmpty() {
        return requests.isEmpty();
    }

    List<Request> requests() {
        return new ArrayList<>(requests);
    }

    void resolve(Request request, Set<String> ids) {
        results.put(request, ids);
    }

    void markResolved() {
        resolved = true;
    }
}
