package com.bjarne.genericgraphql.remote.trust;

import com.bjarne.genericgraphql.remote.SimulatedRemoteService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Steht fuer einen fremden Microservice mit eigener Datenbank. Kein Zugriff auf die
 * JPA-Entities dieser Anwendung - er kennt Profile nur ueber ihre objectRefId.
 */
@Component
public class SimulatedTrustService extends SimulatedRemoteService<TrustInfo> implements TrustServiceClient {

    // Die "Datenbank" des Trust-Service. Er hat keine Historie - nur den aktuellen Stand.
    private static final Map<String, TrustInfo> DATA = Map.of(
            "PROF000001", new TrustInfo(TrustLevel.HIGH, new BigDecimal("0.92"), 0,
                    LocalDate.of(2026, 8, 30), "Ausweis geprueft, Foto-Abgleich ok"),
            "PROF000003", new TrustInfo(TrustLevel.MEDIUM, new BigDecimal("0.71"), 0,
                    LocalDate.of(2026, 7, 12), "Telefonnummer bestaetigt"),
            "PROF000005", new TrustInfo(TrustLevel.LOW, new BigDecimal("0.38"), 2,
                    LocalDate.of(2026, 10, 2), "Zwei offene Meldungen in Pruefung"));

    public SimulatedTrustService(@Value("${remote.trust.latency-ms:120}") long latencyMs) {
        super("Trust-Service", latencyMs);
    }

    @Override
    protected Map<String, TrustInfo> data() {
        return DATA;
    }

    @Override
    public Map<String, TrustInfo> batchGet(Collection<String> profileIds) {
        return respond(profileIds);
    }
}
