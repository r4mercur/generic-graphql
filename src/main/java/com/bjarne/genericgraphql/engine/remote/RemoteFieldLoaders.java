package com.bjarne.genericgraphql.engine.remote;

import com.bjarne.genericgraphql.engine.query.RequestState;
import com.bjarne.genericgraphql.engine.registry.GqlEntityRegistration;
import com.bjarne.genericgraphql.engine.registry.GqlRegistry;
import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.dataloader.DataLoader;
import org.dataloader.DataLoaderFactory;
import org.dataloader.DataLoaderRegistry;
import org.dataloader.MappedBatchLoader;
import org.springframework.stereotype.Component;

@Component
public class RemoteFieldLoaders {

    private final GqlRegistry registry;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public RemoteFieldLoaders(GqlRegistry registry) {
        this.registry = registry;
    }

    public void registerAll(DataLoaderRegistry dataLoaders, RequestState state) {
        for (GqlEntityRegistration registration : registry.all()) {
            for (RemoteField<?> field : registration.remoteFields()) {
                dataLoaders.register(field.loaderKey(registration.typeName()), loader(field, state));
            }
        }
    }

    private <V> DataLoader<String, V> loader(RemoteField<V> field, RequestState state) {
        MappedBatchLoader<String, V> batch = keys -> call(field, keys, state);
        return DataLoaderFactory.newMappedDataLoader(batch);
    }

    // Bewusst synchron: der Aufruf laeuft auf einem eigenen Thread (fuer den Timeout), der
    // Request-Thread wartet. Wuerde das Future erst spaeter fertig, liefe der Rest der Query
    // auf dem fremden Thread weiter - ausserhalb der Transaktion und des EntityManagers, an
    // denen die lokalen DataLoader haengen.
    private <V> CompletableFuture<Map<String, V>> call(RemoteField<V> field, Set<String> keys, RequestState state) {
        RemoteCallContext context = new RemoteCallContext(state.date(), state.graphQLContext());
        Future<Map<String, ? extends V>> pending = executor.submit(() -> field.loader().load(Set.copyOf(keys), context));
        try {
            Map<String, ? extends V> result = pending.get(field.timeout().toMillis(), TimeUnit.MILLISECONDS);
            Map<String, V> values = new HashMap<>();
            if (result != null) {
                values.putAll(result);
            }
            return CompletableFuture.completedFuture(values);
        } catch (TimeoutException ex) {
            pending.cancel(true);
            return CompletableFuture.failedFuture(new RemoteFieldException(
                    field.source() + " hat nicht innerhalb von " + field.timeout().toMillis() + " ms geantwortet", ex));
        } catch (ExecutionException ex) {
            return CompletableFuture.failedFuture(new RemoteFieldException(
                    field.source() + " nicht erreichbar: " + ex.getCause().getMessage(), ex.getCause()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return CompletableFuture.failedFuture(new RemoteFieldException(
                    "Aufruf an " + field.source() + " unterbrochen", ex));
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    public static class RemoteFieldException extends RuntimeException {
        public RemoteFieldException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
