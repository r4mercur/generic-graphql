package com.bjarne.genericgraphql.engine.probe;

/** Haelt die {@link EngineProbe} des Requests am Request-Thread. */
public final class EngineProbes {

    private static final ThreadLocal<EngineProbe> CURRENT = new ThreadLocal<>();

    private EngineProbes() {
    }

    public static EngineProbe current() {
        EngineProbe probe = CURRENT.get();
        return probe != null ? probe : EngineProbe.NOOP;
    }

    /** Bindet die Probe an diesen Thread und liefert die vorherige (oder {@code null}). */
    public static EngineProbe bind(EngineProbe probe) {
        EngineProbe previous = CURRENT.get();
        CURRENT.set(probe);
        return previous;
    }

    /** Stellt den Zustand vor {@link #bind} wieder her. */
    public static void restore(EngineProbe previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
