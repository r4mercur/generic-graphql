package com.bjarne.genericgraphql.remote.trust;

import java.util.Collection;
import java.util.Map;

/**
 * Client fuer den Trust-Service (Verifizierung, Meldungen, Vertrauensstufe). In einer echten
 * Landschaft steckt dahinter ein HTTP-Aufruf wie {@code POST /trust/profiles:batchGet}.
 */
public interface TrustServiceClient {

    /** Ein Aufruf fuer viele Profile. Unbekannte IDs fehlen in der Antwort. */
    Map<String, TrustInfo> batchGet(Collection<String> profileIds);
}
