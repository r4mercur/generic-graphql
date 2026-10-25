package com.bjarne.genericgraphql.remote.trust;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Steht fuer einen fremden Microservice mit eigener Datenbank. Kein Zugriff auf die
 * JPA-Entities dieser Anwendung - er kennt Profile nur ueber ihre objectRefId.
 *
 * <p>Simuliert Netzwerklatenz und Ausfaelle und protokolliert jeden Aufruf, damit sich
 * Batching und Fehlerverhalten beobachten (und testen) lassen.
 */
@Component
public class SimulatedTrustService implements TrustServiceClient {

    private static final Logger log = LoggerFactory.getLogger(SimulatedTrustService.class);

    // Die "Datenbank" des Trust-Service. Er hat keine Historie - nur den aktuellen Stand.
    private static final Map<String, TrustInfo> DATA = Map.of(
            "PROF000001", new TrustInfo(TrustLevel.HIGH, new BigDecimal("0.92"), 0,
                    LocalDate.of(2026, 8, 30), "Ausweis geprueft, Foto-Abgleich ok"),
            "PROF000003", new TrustInfo(TrustLevel.MEDIUM, new BigDecimal("0.71"), 0,
                    LocalDate.of(2026, 7, 12), "Telefonnummer bestaetigt"),
            "PROF000005", new TrustInfo(TrustLevel.LOW, new BigDecimal("0.38"), 2,
                    LocalDate.of(2026, 10, 2), "Zwei offene Meldungen in Pruefung"));

    private final long latencyMs;
    private final List<Set<String>> calls = new CopyOnWriteArrayList<>();
    private volatile boolean available = true;

    public SimulatedTrustService(@Value("${remote.trust.latency-ms:120}") long latencyMs) {
        this.latencyMs = latencyMs;
    }

    @Override
    public Map<String, TrustInfo> batchGet(Collection<String> profileIds) {
        calls.add(Set.copyOf(profileIds));
        log.info("Trust-Service: 1 Aufruf fuer {} Profile {}", profileIds.size(), profileIds);

        sleep(latencyMs);
        if (!available) {
            throw new IllegalStateException("503 Service Unavailable");
        }

        Map<String, TrustInfo> result = new LinkedHashMap<>();
        for (String id : profileIds) {
            TrustInfo info = DATA.get(id);
            if (info != null) {
                result.put(id, info);
            }
        }
        return result;
    }

    // --- Steuerung fuer Demo und Tests --------------------------------------

    public List<Set<String>> calls() {
        return List.copyOf(calls);
    }

    public void reset() {
        calls.clear();
        available = true;
    }

    public void available(boolean value) {
        this.available = value;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Aufruf abgebrochen", ex);
        }
    }
}
