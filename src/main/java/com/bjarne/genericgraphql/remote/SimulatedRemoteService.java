package com.bjarne.genericgraphql.remote;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gemeinsame Mechanik der simulierten Microservices: Netzwerklatenz, Ausfall auf Knopfdruck,
 * eine Suche in der gemeinsamen Filtersprache und ein Protokoll jedes Aufrufs mit Start- und
 * Endzeit. Damit lassen sich Batching, Parallelitaet und Fehlerverhalten beobachten (und testen).
 */
public abstract class SimulatedRemoteService<V> {

    private static final Logger log = LoggerFactory.getLogger(SimulatedRemoteService.class);

    public record Call(Set<String> ids, long startedNanos, long finishedNanos) {

        public boolean overlaps(Call other) {
            return startedNanos < other.finishedNanos && other.startedNanos < finishedNanos;
        }
    }

    private final String name;
    private final long latencyMs;
    private final Set<String> filterable;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final List<Call> searches = new CopyOnWriteArrayList<>();
    private volatile boolean available = true;

    /** {@code filterable}: die Felder, die dieser Service zum Filtern freigibt - sein Teil des Vertrags. */
    protected SimulatedRemoteService(String name, long latencyMs, Set<String> filterable) {
        this.name = name;
        this.latencyMs = latencyMs;
        this.filterable = filterable;
    }

    /** Die "Datenbank" des Service: was er zu einer ID weiss. */
    protected abstract Map<String, V> data();

    protected Map<String, V> respond(Collection<String> ids) {
        long started = System.nanoTime();
        log.info("{}: 1 Aufruf fuer {} Profile {}", name, ids.size(), ids);
        try {
            simulateNetwork();
            Map<String, V> result = new LinkedHashMap<>();
            for (String id : ids) {
                V value = data().get(id);
                if (value != null) {
                    result.put(id, value);
                }
            }
            return result;
        } finally {
            calls.add(new Call(Set.copyOf(ids), started, System.nanoTime()));
        }
    }

    protected List<String> searchIds(Map<String, Object> filter, int limit) {
        long started = System.nanoTime();
        List<String> hits = List.of();
        try {
            simulateNetwork();
            hits = data().entrySet().stream()
                    .filter(entry -> InMemoryFilter.matches(entry.getValue(), filter, filterable))
                    .map(Map.Entry::getKey)
                    .sorted()
                    .limit(limit)
                    .toList();
            log.info("{}: Suche {} -> {} Treffer {}", name, filter, hits.size(), hits);
            return hits;
        } finally {
            searches.add(new Call(Set.copyOf(hits), started, System.nanoTime()));
        }
    }

    // --- Steuerung fuer Demo und Tests --------------------------------------

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public List<Call> searches() {
        return List.copyOf(searches);
    }

    public void reset() {
        calls.clear();
        searches.clear();
        available = true;
    }

    public void available(boolean value) {
        this.available = value;
    }

    private void simulateNetwork() {
        try {
            Thread.sleep(latencyMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Aufruf abgebrochen", ex);
        }
        if (!available) {
            throw new IllegalStateException("503 Service Unavailable");
        }
    }
}
