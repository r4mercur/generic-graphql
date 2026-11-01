package com.bjarne.genericgraphql.engine.probe;

import java.util.Collection;

/**
 * Messpunkte der Engine fuer die Live-Ansicht (Inspector).
 *
 * <p>Die Engine meldet hier, was sie tut: DataLoader-Batches, Aufrufe an fremde Services,
 * Wartezeiten des Request-Threads, interne Schritte einer Mutation und SQL-Statements. Ohne
 * Inspector ist {@link #current()} ein No-op - die Engine haengt also nicht vom Inspector ab.
 *
 * <p>Die Probe gilt pro Request und haengt am Request-Thread ({@link EngineProbes}). Arbeit auf
 * anderen Threads (virtuelle Threads der Remote-Aufrufe) holt sich die Probe vorher auf dem
 * Request-Thread und reicht sie weiter.
 */
public interface EngineProbe {

    EngineProbe NOOP = new EngineProbe() {
    };

    /** Die Probe des Requests, der gerade auf diesem Thread laeuft - sonst {@link #NOOP}. */
    static EngineProbe current() {
        return EngineProbes.current();
    }

    /** Ein DataLoader der eigenen Datenbank fuehrt einen Batch aus. */
    default Span batch(String loaderKey, Collection<?> keys) {
        return Span.NOOP;
    }

    /** Ein Aufruf an einen fremden Service fuer einen Remote-Loader (batchGet). Laeuft auf dem aufrufenden Thread. */
    default Span remoteCall(String source, String loaderKey, Collection<?> keys) {
        return Span.NOOP;
    }

    /** Die Suche eines fremden Service fuer einen delegierten Filter-Teilbaum. */
    default Span remoteSearch(String source, String field, String filter) {
        return Span.NOOP;
    }

    /** Der Request-Thread wartet - auf Remote-Aufrufe einer Ebene oder auf eine Remote-Suche. */
    default Span waiting(String label) {
        return Span.NOOP;
    }

    /** Ein interner Schritt, z. B. in {@code EventMutationService}. {@code verb} ist die Kurzform fuer die Kante. */
    default Span step(String name, String verb) {
        return Span.NOOP;
    }

    /** Ein SQL-Statement auf diesem Thread. */
    default Span sql(String sql) {
        return Span.NOOP;
    }

    /** Ein offener Messpunkt. {@link #close()} beendet ihn, mehrfaches Schliessen ist erlaubt. */
    interface Span extends AutoCloseable {

        Span NOOP = new Span() {
        };

        /** Anzahl Zeilen, Treffer oder Werte. */
        default Span result(long count) {
            return this;
        }

        /** Kurzes Ergebnis als Text, z. B. eine neue ID. */
        default Span note(String text) {
            return this;
        }

        default void fail(Throwable error) {
        }

        @Override
        default void close() {
        }
    }
}
