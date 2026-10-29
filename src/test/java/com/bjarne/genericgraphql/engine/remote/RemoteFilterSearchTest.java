package com.bjarne.genericgraphql.engine.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bjarne.genericgraphql.remote.trust.TrustInfo;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RemoteFilterSearchTest {

    private static final Map<String, Object> FILTER = Map.of("level", Map.of("exact", "HIGH"));
    private static final RemoteCallContext CONTEXT = new RemoteCallContext(LocalDate.now(), null);

    private final RemoteFilterSearch search = new RemoteFilterSearch();

    @AfterEach
    void tearDown() {
        search.shutdown();
    }

    @Test
    void asksOneMoreThanAllowedAndRejectsTooManyHits() {
        AtomicInteger requestedLimit = new AtomicInteger();
        RemoteField<TrustInfo> field = field(2, Duration.ofSeconds(1), (filter, limit, context) -> {
            requestedLimit.set(limit);
            return List.of("A", "B", "C").subList(0, Math.min(3, limit));
        });

        RemoteFilterPlan plan = new RemoteFilterPlan();
        plan.ids(field, FILTER);

        assertThatThrownBy(() -> search.resolve(plan, CONTEXT))
                .isInstanceOf(RemoteFieldException.class)
                .hasMessage("Filter auf trust trifft mehr als 2 Objekte im Trust-Service - bitte weiter einschraenken");
        assertThat(requestedLimit).hasValue(3);
    }

    @Test
    void exactlyMaxIdsIsAccepted() {
        RemoteField<TrustInfo> field = field(2, Duration.ofSeconds(1), (filter, limit, context) -> List.of("A", "B"));

        RemoteFilterPlan plan = new RemoteFilterPlan();
        plan.ids(field, FILTER);
        search.resolve(plan, CONTEXT);

        assertThat(plan.ids(field, FILTER)).containsExactly("A", "B");
    }

    @Test
    void sameSubtreeIsAskedOnlyOnce() {
        AtomicInteger calls = new AtomicInteger();
        RemoteField<TrustInfo> field = field(10, Duration.ofSeconds(1), (filter, limit, context) -> {
            calls.incrementAndGet();
            return List.of("A");
        });

        RemoteFilterPlan plan = new RemoteFilterPlan();
        plan.ids(field, FILTER);
        plan.ids(field, Map.of("level", Map.of("exact", "HIGH")));
        search.resolve(plan, CONTEXT);

        assertThat(calls).hasValue(1);
    }

    @Test
    void slowOwnerFailsAfterItsTimeout() {
        RemoteField<TrustInfo> field = field(10, Duration.ofMillis(50), (filter, limit, context) -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return List.of("A");
        });

        RemoteFilterPlan plan = new RemoteFilterPlan();
        plan.ids(field, FILTER);

        assertThatThrownBy(() -> search.resolve(plan, CONTEXT))
                .isInstanceOf(RemoteFieldException.class)
                .hasMessage("Suche im Trust-Service hat nicht innerhalb von 50 ms geantwortet");
    }

    private static RemoteField<TrustInfo> field(int maxIds, Duration timeout, RemoteSearch remoteSearch) {
        return RemoteField.of("trust", TrustInfo.class)
                .source("Trust-Service")
                .timeout(timeout)
                .loader((keys, context) -> Map.of())
                .filterable(remoteSearch, "level")
                .maxIds(maxIds)
                .build();
    }
}
