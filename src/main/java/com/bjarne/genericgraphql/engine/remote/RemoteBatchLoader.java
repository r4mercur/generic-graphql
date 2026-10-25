package com.bjarne.genericgraphql.engine.remote;

import java.util.Map;
import java.util.Set;

/**
 * Ein Aufruf an den fremden Service fuer alle Schluessel einer Query-Ebene.
 * Fehlt ein Schluessel in der Antwort, kennt der Service das Objekt nicht - das Feld wird null.
 */
@FunctionalInterface
public interface RemoteBatchLoader<V> {

    Map<String, ? extends V> load(Set<String> keys, RemoteCallContext context);
}
