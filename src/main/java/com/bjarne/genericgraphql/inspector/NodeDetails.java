package com.bjarne.genericgraphql.inspector;

import java.util.List;

/**
 * Was das Detail-Panel zu einem Knoten zeigt. {@code icon}: root, db, remote oder filter.
 */
public record NodeDetails(String title,
                          String sig,
                          String sourceLabel,
                          String icon,
                          List<Fact> facts,
                          List<ChainStep> chain,
                          String keysTitle,
                          String keysNote,
                          String errorNote,
                          String sqlTitle) {
}
