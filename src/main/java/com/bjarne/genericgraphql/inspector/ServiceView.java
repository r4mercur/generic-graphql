package com.bjarne.genericgraphql.inspector;

/** Ein simulierter fremder Service und ob er gerade antwortet. */
public record ServiceView(String id, String name, boolean available) {
}
