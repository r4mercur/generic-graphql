package com.bjarne.genericgraphql.engine.remote;

import org.dataloader.DataLoaderRegistry;

/**
 * graphql-java loest pro Ebene {@link #dispatchAll()} aus. Hier heisst das: erst alle Loader
 * ausloesen - die Remote-Loader starten dabei ihre Aufrufe parallel -, dann gemeinsam auf
 * die Remote-Aufrufe warten.
 *
 * <p>Setzt die Standard-Dispatch-Strategie voraus. Mit aktiviertem DataLoader-Chaining ruft
 * graphql-java die Loader einzeln auf und {@code dispatchAll} wird uebergangen.
 */
public class RemoteAwareDataLoaderRegistry extends DataLoaderRegistry {

    private final RemoteCalls remoteCalls = new RemoteCalls();

    public RemoteCalls remoteCalls() {
        return remoteCalls;
    }

    @Override
    public void dispatchAll() {
        super.dispatchAll();
        remoteCalls.awaitAndComplete();
    }
}
