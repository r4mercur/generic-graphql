package com.bjarne.genericgraphql.inspector;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Ein mitgeschnittener Request, so wie ihn die Inspector-Seite bekommt (JSON ueber SSE und REST).
 * Zeiten in Millisekunden ab Beginn des Requests.
 *
 * <p>{@code status}: ok, partial (Fehler im errors-Array, Rest der Antwort da), failed (Fehler,
 * keine Daten), rejected (vor der Ausfuehrung abgelehnt: Syntax, Validierung, Guardrails) oder
 * declined (Mutation ohne errors, aber mit result "error" - eine fachliche Ablehnung).
 */
public record TraceView(long id,
                        String operation,
                        String kind,
                        String query,
                        long startedAt,
                        double totalMs,
                        String status,
                        int errorCount,
                        int sqlCount,
                        int remoteCount,
                        String thread,
                        String loaders,
                        List<NodeView> nodes,
                        List<SpanView> spans,
                        List<MarkView> marks,
                        Map<String, Object> response) {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Kurzform fuer die Liste links. */
    public TraceSummary summary() {
        Set<String> sources = new LinkedHashSet<>();
        for (SpanView span : spans) {
            if (!"thread".equals(span.lane())) {
                sources.add(span.lane());
            }
        }
        String time = TIME.format(Instant.ofEpochMilli(startedAt).atZone(ZoneId.systemDefault()));
        return new TraceSummary(id, operation, kind, totalMs, formatMs(totalMs), status, statusLabel(),
                errorCount, sqlCount, remoteCount, List.copyOf(sources), time);
    }

    public String statusLabel() {
        return switch (status) {
            case "partial" -> errorCount == 1 ? "1 Fehler" : errorCount + " Fehler";
            case "failed" -> "Fehler";
            case "rejected" -> "abgelehnt";
            case "declined" -> "result: error";
            default -> "ok";
        };
    }

    public NodeView node(String nodeId) {
        for (NodeView node : nodes) {
            if (node.id().equals(nodeId)) {
                return node;
            }
        }
        return null;
    }

    static String formatMs(double ms) {
        return String.format(Locale.GERMANY, "%.1f ms", ms);
    }
}
