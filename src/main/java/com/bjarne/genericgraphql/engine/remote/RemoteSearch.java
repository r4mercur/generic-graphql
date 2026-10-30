package com.bjarne.genericgraphql.engine.remote;

import java.util.Collection;
import java.util.Map;

/**
 * Die Suche des Owners: wertet einen Filter in der gemeinsamen Filtersprache aus
 * (dieselben Lookups und AND/OR/NOT wie lokal) und liefert die Schluessel aller Treffer.
 * Wie er auswertet und was er dabei sichtbar macht, entscheidet der Owner.
 */
@FunctionalInterface
public interface RemoteSearch {

    /** Hoechstens {@code limit} Schluessel - die Engine fragt einen mehr an, als sie erlaubt. */
    Collection<String> search(Map<String, Object> filter, int limit, RemoteCallContext context);
}
