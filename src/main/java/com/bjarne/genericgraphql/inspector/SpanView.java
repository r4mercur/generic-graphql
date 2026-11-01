package com.bjarne.genericgraphql.inspector;

/**
 * Ein Balken in der Timeline. {@code lane} ist "thread", "db" oder die Kurzform eines fremden
 * Service ("trust", "presence"). {@code kind}: work, level, step, wait, sql, remote, search.
 */
public record SpanView(String lane,
                       String kind,
                       double t0,
                       double t1,
                       String label,
                       String node,
                       String detail,
                       String error,
                       long count,
                       String thread) {
}
