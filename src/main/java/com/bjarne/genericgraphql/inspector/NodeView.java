package com.bjarne.genericgraphql.inspector;

import java.util.List;

/**
 * Ein Knoten im Call Tree: ein Feld (ueber alle Elternobjekte zusammengefasst), ein interner
 * Schritt einer Mutation oder eine Remote-Suche fuer einen Filter. {@code t0} ist der erste
 * Aufruf, {@code t1} der letzte Abschluss, {@code ready} bei Remote-Feldern das Ende des eigenen
 * Service-Aufrufs (danach wartet das Feld auf die Ebene). Zeiten in ms ab Beginn des Requests.
 *
 * <p>{@code kind}: root, list, byId, timestamps, log, relations, mutation, forward, reverse,
 * remote, search, step oder field. {@code source} ist die Spur in der Timeline: root, db oder
 * die Kurzform eines fremden Service (trust, presence).
 */
public record NodeView(String id,
                       String parent,
                       int level,
                       String kind,
                       String path,
                       String field,
                       String sig,
                       String owner,
                       String type,
                       String source,
                       String sourceName,
                       String via,
                       String resolver,
                       String loaderKey,
                       int qline,
                       List<String> chips,
                       boolean aux,
                       boolean step,
                       double t0,
                       double t1,
                       Double ready,
                       int loads,
                       long items,
                       String count,
                       String ret,
                       List<String> keys,
                       int batches,
                       List<String> sql,
                       long rows,
                       String thread,
                       String args,
                       String error) {

    public boolean failed() {
        return error != null;
    }

    public int sqlCount() {
        return sql.size();
    }
}
