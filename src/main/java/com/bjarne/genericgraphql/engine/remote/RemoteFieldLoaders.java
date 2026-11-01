package com.bjarne.genericgraphql.engine.remote;

import com.bjarne.genericgraphql.engine.probe.EngineProbe;
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
                String loaderKey = field.loaderKey(registration.typeName());
                dataLoaders.register(loaderKey, loader(field, loaderKey, state, dataLoaders.remoteCalls()));
            }
        }
    }

    private <V> DataLoader<String, V> loader(RemoteField<V> field, String loaderKey, RequestState state, RemoteCalls calls) {
        MappedBatchLoader<String, V> batch = keys -> start(field, loaderKey, keys, state, calls);
        return DataLoaderFactory.newMappedDataLoader(batch);
    }

    // Startet den Aufruf nur. Gewartet wird in RemoteCalls - gemeinsam fuer alle
    // Remote-Felder einer Ebene, auf dem Request-Thread.
    private <V> CompletableFuture<Map<String, V>> start(RemoteField<V> field,
                                                       String loaderKey,
                                                       Set<String> keys,
                                                       RequestState state,
                                                       RemoteCalls calls) {
        RemoteCallContext context = new RemoteCallContext(state.date(), state.graphQLContext());
        // Die Probe haengt am Request-Thread - hier holen, im virtuellen Thread benutzen.
        EngineProbe probe = EngineProbe.current();
        Set<String> ids = Set.copyOf(keys);
        Future<Map<String, ? extends V>> call = executor.submit(() -> {
            try (EngineProbe.Span span = probe.remoteCall(field.source(), loaderKey, ids)) {
                try {
                    Map<String, ? extends V> result = field.loader().load(ids, context);
                    span.result(result == null ? 0 : result.size());
                    return result;
                } catch (RuntimeException ex) {
                    span.fail(ex);
                    throw ex;
                }
            }
        });
        return calls.register(field, call);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
