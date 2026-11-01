package com.bjarne.genericgraphql.inspector;

import java.util.List;

/** Eine Zeile der Request-Liste. {@code total} ist bereits formatiert ("12,4 ms"). */
public record TraceSummary(long id,
                           String operation,
                           String kind,
                           double totalMs,
                           String total,
                           String status,
                           String statusLabel,
                           int errorCount,
                           int sqlCount,
                           int remoteCount,
                           List<String> sources,
                           String time) {
}
