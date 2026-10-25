package com.bjarne.genericgraphql.remote.presence;

import java.util.Collection;
import java.util.Map;

/**
 * Client fuer den Presence-Service (online, schreibt gerade, Geraet). In einer echten
 * Landschaft steckt dahinter ein HTTP-Aufruf wie {@code POST /presence/profiles:batchGet}.
 */
public interface PresenceServiceClient {

    /** Ein Aufruf fuer viele Profile. Unbekannte IDs fehlen in der Antwort. */
    Map<String, PresenceInfo> batchGet(Collection<String> profileIds);
}
