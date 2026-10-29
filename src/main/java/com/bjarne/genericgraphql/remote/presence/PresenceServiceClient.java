package com.bjarne.genericgraphql.remote.presence;

import java.util.Collection;
import java.util.Map;

/**
 * Client fuer den Presence-Service (online, schreibt gerade, Geraet). In einer echten
 * Landschaft steckt dahinter ein HTTP-Aufruf wie {@code POST /presence/profiles:batchGet} - bzw.
 * fuer die Suche eine Query an die generische Engine des Presence-Service.
 */
public interface PresenceServiceClient {

    /** Ein Aufruf fuer viele Profile. Unbekannte IDs fehlen in der Antwort. */
    Map<String, PresenceInfo> batchGet(Collection<String> profileIds);

    /** Profil-IDs, auf die der Filter zutrifft - in der gemeinsamen Filtersprache, hoechstens limit. */
    Collection<String> search(Map<String, Object> filter, int limit);
}
