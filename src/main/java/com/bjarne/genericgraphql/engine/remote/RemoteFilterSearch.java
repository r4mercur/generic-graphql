package com.bjarne.genericgraphql.engine.remote;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/**
 * Fuehrt einen {@link RemoteFilterPlan} aus: alle Owner gleichzeitig fragen, dann auf alle
 * warten. Anders als beim Anzeigen gibt es hier kein Teilergebnis - ohne die Treffer des
 * Owners waere die Trefferliste der Query falsch, also scheitert sie.
 */
@Component
public class RemoteFilterSearch {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private record Running(RemoteFilterPlan.Request request, Future<Collection<String>> call, long deadlineNanos) {
    }

    public void resolve(RemoteFilterPlan plan, RemoteCallContext context) {
        List<Running> running = new ArrayList<>();
        for (RemoteFilterPlan.Request request : plan.requests()) {
            RemoteField<?> field = request.field();
            // Einen mehr anfragen als erlaubt - so ist "zu viele" von "genau maxIds" unterscheidbar.
            int limit = field.maxIds() + 1;
            running.add(new Running(request,
                    executor.submit(() -> field.search().search(request.filter(), limit, context)),
                    System.nanoTime() + field.timeout().toNanos()));
        }

        try {
            for (Running search : running) {
                plan.resolve(search.request(), await(search));
            }
        } catch (RuntimeException ex) {
            running.forEach(search -> search.call().cancel(true));
            throw ex;
        }
        plan.markResolved();
    }

    private static Set<String> await(Running search) {
        RemoteField<?> field = search.request().field();
        try {
            long remaining = Math.max(0, search.deadlineNanos() - System.nanoTime());
            Collection<String> hits = search.call().get(remaining, TimeUnit.NANOSECONDS);
            Set<String> ids = hits == null ? Set.of() : new LinkedHashSet<>(hits);
            if (ids.size() > field.maxIds()) {
                throw new RemoteFieldException("Filter auf " + field.name() + " trifft mehr als " + field.maxIds()
                        + " Objekte im " + field.source() + " - bitte weiter einschraenken");
            }
            return ids;
        } catch (TimeoutException ex) {
            throw new RemoteFieldException("Suche im " + field.source() + " hat nicht innerhalb von "
                    + field.timeout().toMillis() + " ms geantwortet", ex);
        } catch (ExecutionException ex) {
            throw new RemoteFieldException("Suche im " + field.source() + " fehlgeschlagen: "
                    + ex.getCause().getMessage(), ex.getCause());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RemoteFieldException("Suche im " + field.source() + " unterbrochen", ex);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
