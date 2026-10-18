package com.bjarne.genericgraphql;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Liest benannte GraphQL-Operationen woertlich aus einem Text - einer Markdown-Doku
 * oder der GraphiQL-Vorlage. Auskommentierte Beispiele ("# query Foo {") werden
 * dabei mitgenommen, damit auch sie gegen die laufende API geprueft werden.
 */
final class DocumentedExamples {

    private static final Pattern OPERATION_START = Pattern.compile("^(query|mutation)\\s+(\\w+)\\s*\\{");

    private DocumentedExamples() {
    }

    /** Operationsname -> Query-Text, in der Reihenfolge des Vorkommens. */
    static Map<String, String> extract(String text) {
        Map<String, String> operations = new LinkedHashMap<>();
        StringBuilder current = null;
        String name = null;
        int depth = 0;

        for (String rawLine : text.split("\\R")) {
            String line = rawLine.startsWith("#") ? rawLine.replaceFirst("^# ?", "") : rawLine;
            if (current == null) {
                Matcher start = OPERATION_START.matcher(line);
                if (!start.find()) {
                    continue;
                }
                current = new StringBuilder();
                name = start.group(2);
            }
            current.append(line).append('\n');
            depth += count(line, '{') - count(line, '}');
            if (depth == 0) {
                if (operations.put(name, current.toString()) != null) {
                    throw new IllegalStateException("Beispiel doppelt benannt: " + name);
                }
                current = null;
            }
        }
        if (current != null) {
            throw new IllegalStateException("Beispiel ohne schliessende Klammer: " + name);
        }
        return operations;
    }

    private static int count(String line, char c) {
        return (int) line.chars().filter(ch -> ch == c).count();
    }
}
