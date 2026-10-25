package com.bjarne.genericgraphql.engine.remote;

import com.bjarne.genericgraphql.engine.query.RequestState;
import com.bjarne.genericgraphql.engine.registry.GqlEntityRegistration;
import com.bjarne.genericgraphql.engine.registry.GqlRegistry;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.dataloader.DataLoader;
import org.dataloader.DataLoaderFactory;
import org.dataloader.MappedBatchLoader;
import org.springframework.stereotype.Component;

@Component
public class RemoteFieldLoaders {

    private final GqlRegistry registry;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public RemoteFieldLoaders(GqlRegistry registry) {
        this.registry = registry;
    }

    public void registerAll(RemoteAwareDataLoaderRegistry dataLoaders, RequestState state) {
        for (GqlEntityRegistration registration : registry.all()) {
            for (RemoteField<?> field : registration.remoteFields()) {
                dataLoaders.register(field.loaderKey(registration.typeName()),
                        loader(field, state, dataLoaders.remoteCalls()));
            }
        }
    }

    private <V> DataLoader<String, V> loader(RemoteField<V> field, RequestState state, RemoteCalls calls) {
        MappedBatchLoader<String, V> batch = keys -> start(field, keys, state, calls);
        return DataLoaderFactory.newMappedDataLoader(batch);
    }

    // Startet den Aufruf nur. Gewartet wird in RemoteCalls - gemeinsam fuer alle
    // Remote-Felder einer Ebene, auf dem Request-Thread.
    private <V> CompletableFuture<Map<String, V>> start(RemoteField<V> field,
                                                       Set<String> keys,
                                                       RequestState state,
                                                       RemoteCalls calls) {
        RemoteCallContext context = new RemoteCallContext(state.date(), state.graphQLContext());
        Future<Map<String, ? extends V>> call = executor.submit(() -> field.loader().load(Set.copyOf(keys), context));
        return calls.register(field, call);
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
