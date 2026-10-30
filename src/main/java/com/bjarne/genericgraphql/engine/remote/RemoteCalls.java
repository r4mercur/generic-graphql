package com.bjarne.genericgraphql.engine.remote;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Die Remote-Aufrufe eines Requests, die gestartet, aber noch nicht abgeschlossen sind.
 *
 * <p>Die Remote-Loader starten ihre Aufrufe nur und kehren sofort zurueck. Erst wenn alle
 * Loader einer Ebene ausgeloest sind, wartet {@link #awaitAndComplete()} auf alle zusammen -
 * die Wartezeit einer Ebene ist damit die des langsamsten Service, nicht die Summe. Die
 * Futures werden auf dem aufrufenden Thread abgeschlossen, also auf dem Request-Thread, auf
 * dem auch Transaktion und EntityManager der lokalen DataLoader liegen.
 */
public final class RemoteCalls {

    // Reserve, falls awaitAndComplete nie aufgerufen wird (z. B. bei einer anderen
    // Dispatch-Strategie): lieber ein Fehler im Feld als ein haengender Request.
    private static final long SAFETY_MARGIN_MS = 1_000;

    private final List<Pending<?>> pending = new ArrayList<>();

    synchronized <V> CompletableFuture<Map<String, V>> register(RemoteField<V> field,
                                                               Future<Map<String, ? extends V>> call) {
        CompletableFuture<Map<String, V>> result = new CompletableFuture<>();
        result.orTimeout(field.timeout().toMillis() + SAFETY_MARGIN_MS, TimeUnit.MILLISECONDS);
        pending.add(new Pending<>(field, call, result, System.nanoTime() + field.timeout().toNanos()));
        return result;
    }

    /**
     * Wartet auf alle offenen Aufrufe und schliesst danach ihre Futures ab. Das Abschliessen
     * setzt die Query fort und kann die naechste Ebene ausloesen - deren Aufrufe landen in
     * einer neuen Runde.
     */
    public void awaitAndComplete() {
        while (true) {
            List<Pending<?>> round;
            synchronized (this) {
                if (pending.isEmpty()) {
                    return;
                }
                round = new ArrayList<>(pending);
                pending.clear();
            }
            // Erst alle Ergebnisse einsammeln, dann abschliessen: so wartet keine Antwort
            // darauf, dass die Fortsetzung einer anderen fertig wird.
            List<Runnable> completions = new ArrayList<>(round.size());
            for (Pending<?> call : round) {
                completions.add(call.await());
            }
            completions.forEach(Runnable::run);
        }
    }

    private record Pending<V>(RemoteField<V> field,
                              Future<Map<String, ? extends V>> call,
                              CompletableFuture<Map<String, V>> result,
                              long deadlineNanos) {

        /** Der Timeout zaehlt ab dem Start des Aufrufs, nicht ab dem Beginn des Wartens. */
        Runnable await() {
            try {
                long remaining = Math.max(0, deadlineNanos - System.nanoTime());
                Map<String, ? extends V> response = call.get(remaining, TimeUnit.NANOSECONDS);
                Map<String, V> values = new HashMap<>();
                if (response != null) {
                    values.putAll(response);
                }
                return () -> result.complete(values);
            } catch (TimeoutException ex) {
                call.cancel(true);
                return fail(field.source() + " hat nicht innerhalb von " + field.timeout().toMillis()
                        + " ms geantwortet", ex);
            } catch (ExecutionException ex) {
                return fail(field.source() + " nicht erreichbar: " + ex.getCause().getMessage(), ex.getCause());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                call.cancel(true);
                return fail("Aufruf an " + field.source() + " unterbrochen", ex);
            }
        }

        private Runnable fail(String message, Throwable cause) {
            return () -> result.completeExceptionally(new RemoteFieldException(message, cause));
        }
    }
}
