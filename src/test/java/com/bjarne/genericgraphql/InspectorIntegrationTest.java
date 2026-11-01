package com.bjarne.genericgraphql;

import static org.assertj.core.api.Assertions.assertThat;

import com.bjarne.genericgraphql.inspector.Example;
import com.bjarne.genericgraphql.inspector.InspectorExamples;
import com.bjarne.genericgraphql.inspector.NodeView;
import com.bjarne.genericgraphql.inspector.SpanView;
import com.bjarne.genericgraphql.inspector.TraceHub;
import com.bjarne.genericgraphql.inspector.TraceView;
import com.bjarne.genericgraphql.remote.presence.SimulatedPresenceService;
import com.bjarne.genericgraphql.remote.trust.SimulatedTrustService;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.client.RestClient;

/**
 * Der Inspector schneidet Requests an POST /graphql mit: Felder je Ebene, DataLoader-Batches,
 * SQL, Remote-Aufrufe, Mutations-Schritte - und liefert sie per REST, SSE und Thymeleaf aus.
 * Die Queries sind die Beispiele der Seite, woertlich aus der Doku.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:inspector;DB_CLOSE_DELAY=-1")
class InspectorIntegrationTest {

    private static final List<String> ALL_PROFILES = List.of("PROF000001", "PROF000003", "PROF000005");

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private TraceHub hub;

    @Autowired
    private SimulatedTrustService trustService;

    @Autowired
    private SimulatedPresenceService presenceService;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.create("http://localhost:" + port);
        trustService.reset();
        presenceService.reset();
        hub.clear();
    }

    @AfterEach
    void tearDown() {
        trustService.reset();
        presenceService.reset();
    }

    // ------------------------------------------------------------------ Beispiele

    @Test
    void examplesAreTheQueriesFromTheDocs() throws IOException {
        Map<String, String> documented = new LinkedHashMap<>();
        documented.putAll(DocumentedExamples.extract(Files.readString(Path.of("docs/GQL2-JAVA-IMPLEMENTIERUNG.md"))));
        documented.putAll(DocumentedExamples.extract(Files.readString(Path.of("docs/GQL2-MICROSERVICES.md"))));
        for (Example example : InspectorExamples.ALL) {
            assertThat(documented).as(example.name()).containsEntry(example.name(), example.query());
        }
    }

    // ------------------------------------------------------------------ Struktur des Mitschnitts

    @Test
    void trustBadgeShowsOneBatchForBothProfilesAndOneRemoteCall() {
        Map<String, Object> response = graphql(example("TrustBadge"));
        assertThat(response).doesNotContainKey("errors");

        TraceView trace = latest();
        assertThat(trace.operation()).isEqualTo("TrustBadge");
        assertThat(trace.kind()).isEqualTo("query");
        assertThat(trace.status()).isEqualTo("ok");
        assertThat(trace.response()).containsKey("data").doesNotContainKey("errors");

        NodeView root = trace.nodes().getFirst();
        assertThat(root.kind()).isEqualTo("root");
        NodeView matches = node(trace, "/matchLatestVersions");
        assertThat(matches.kind()).isEqualTo("list");
        assertThat(matches.parent()).isEqualTo(root.id());
        assertThat(matches.items()).isEqualTo(2);
        assertThat(matches.chips()).containsExactly("objectRefId");

        NodeView profileA = node(trace, "/matchLatestVersions/profileA");
        NodeView profileB = node(trace, "/matchLatestVersions/profileB");
        assertThat(profileA.kind()).isEqualTo("forward");
        assertThat(profileA.level()).isEqualTo(2);
        assertThat(profileA.loads()).isEqualTo(2);
        // 4x load() fuer A und B, 3 verschiedene Profile, ein Batch fuer beide Felder
        assertThat(profileA.keys()).containsExactlyInAnyOrderElementsOf(ALL_PROFILES);
        assertThat(profileA.batches()).isEqualTo(1);
        assertThat(profileA.sql()).hasSize(1).isEqualTo(profileB.sql());

        NodeView trustA = node(trace, "/matchLatestVersions/profileA/trust");
        NodeView trustB = node(trace, "/matchLatestVersions/profileB/trust");
        assertThat(trustA.kind()).isEqualTo("remote");
        assertThat(trustA.source()).isEqualTo("trust");
        assertThat(trustA.parent()).isEqualTo(profileA.id());
        assertThat(trustA.level()).isEqualTo(3);
        assertThat(trustA.keys()).containsExactlyInAnyOrderElementsOf(ALL_PROFILES);
        assertThat(trustA.ready()).isNotNull().isLessThanOrEqualTo(trustA.t1());
        assertThat(trustA.thread()).startsWith("virtual-");
        assertThat(trustB.chips()).containsExactly("level", "openReports");

        assertThat(spans(trace, "trust")).hasSize(1);
        assertThat(trace.remoteCount()).isEqualTo(1);
        assertThat(trace.sqlCount()).isEqualTo(2);
        assertThat(trace.marks()).extracting(mark -> mark.label()).containsExactly("E1", "E2", "E3");
        assertThat(spans(trace, "thread")).extracting(SpanView::kind).contains("wait");
        assertThat(trace.totalMs()).isGreaterThanOrEqualTo(trustA.t1());
    }

    @Test
    void sqlIsCapturedWithTextAndRows() {
        graphql(example("MatchCard"));
        TraceView trace = latest();

        NodeView matches = node(trace, "/matchLatestVersions");
        assertThat(matches.sql()).hasSize(1);
        assertThat(matches.sql().getFirst()).containsIgnoringCase("from gql_match");
        assertThat(matches.rows()).isEqualTo(2);

        NodeView photos = node(trace, "/matchLatestVersions/profileB/photos");
        assertThat(photos.kind()).isEqualTo("reverse");
        assertThat(photos.sql().getFirst()).containsIgnoringCase("gql_photo").contains("'PROF0000");

        List<SpanView> db = spans(trace, "db");
        assertThat(db).hasSize(trace.sqlCount());
        assertThat(db).allSatisfy(span -> {
            assertThat(span.label()).startsWith("SELECT");
            assertThat(span.count()).isGreaterThanOrEqualTo(0);
            assertThat(span.t1()).isGreaterThanOrEqualTo(span.t0());
        });
        // Eine Abfrage je Batch, nicht je Elternobjekt
        assertThat(trace.sqlCount()).isLessThanOrEqualTo(7);
    }

    @Test
    void parallelServicesOnOneLevelAreCapturedSeparately() {
        graphql(example("ProfileStatus"));
        TraceView trace = latest();

        NodeView trust = node(trace, "/profileLatestVersions/trust");
        NodeView presence = node(trace, "/profileLatestVersions/presence");
        assertThat(trust.source()).isEqualTo("trust");
        assertThat(presence.source()).isEqualTo("presence");
        SpanView trustCall = spans(trace, "trust").getFirst();
        SpanView presenceCall = spans(trace, "presence").getFirst();
        // gleichzeitig unterwegs
        assertThat(trustCall.t0()).isLessThan(presenceCall.t1());
        assertThat(presenceCall.t0()).isLessThan(trustCall.t1());
        assertThat(trustCall.thread()).isNotEqualTo(presenceCall.thread());
    }

    @Test
    void remoteFilterSearchAppearsAsAuxiliaryNodeBeforeTheSql() {
        graphql(example("TrustedInHamburg"));
        TraceView trace = latest();

        NodeView profiles = node(trace, "/profileLatestVersions");
        NodeView search = trace.nodes().stream().filter(NodeView::aux).findFirst().orElseThrow();
        assertThat(search.kind()).isEqualTo("search");
        assertThat(search.parent()).isEqualTo(profiles.id());
        assertThat(search.args()).contains("score").contains("0.7");
        assertThat(search.keys()).containsExactly("PROF000001", "PROF000003");
        assertThat(search.t1()).isLessThanOrEqualTo(spans(trace, "db").getFirst().t0());
        assertThat(spans(trace, "trust")).extracting(SpanView::kind).containsExactly("search", "remote");
    }

    // ------------------------------------------------------------------ Ausfall eines Service

    @Test
    void outageShowsPartialResultAndFailedFilter() {
        trustService.available(false);

        graphql(example("TrustBadge"));
        TraceView badge = latest();
        assertThat(badge.status()).isEqualTo("partial");
        assertThat(badge.errorCount()).isEqualTo(4);
        assertThat(node(badge, "/matchLatestVersions/profileA/trust").error()).contains("503");
        assertThat(node(badge, "/matchLatestVersions/profileA").error()).isNull();
        assertThat(spans(badge, "trust").getFirst().error()).contains("503");

        graphql(example("TrustedInHamburg"));
        TraceView filter = latest();
        assertThat(filter.status()).isEqualTo("failed");
        assertThat(node(filter, "/profileLatestVersions").error()).contains("Trust-Service");
        assertThat(filter.nodes()).filteredOn(NodeView::aux).singleElement()
                .satisfies(search -> assertThat(search.error()).contains("503"));
    }

    @Test
    void servicesCanBeSwitchedOffFromThePage() {
        Map<String, Object> off = client.post().uri("/inspector/api/services/trust")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("available", false))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        assertThat(off).containsEntry("available", false);
        assertThat(trustService.isAvailable()).isFalse();
    }

    // ------------------------------------------------------------------ Mutationen

    // Die Mutation zieht Jonas nach Bremen - danach findet TrustedInHamburg niemanden mehr.
    // Frischer Kontext (und damit frisch geseedete Datenbank) fuer die Tests danach.
    @Test
    @DirtiesContext
    void updateProfileShowsTheStepsOfTheEventMutation() {
        Map<String, Object> response = graphql(example("UpdateProfile"));
        assertThat(response).doesNotContainKey("errors");

        TraceView trace = latest();
        assertThat(trace.kind()).isEqualTo("mutation");
        assertThat(trace.status()).isEqualTo("ok");
        NodeView event = node(trace, "/profileEvent");
        assertThat(event.kind()).isEqualTo("mutation");
        assertThat(event.count()).isEqualTo("ok");

        List<NodeView> steps = trace.nodes().stream().filter(NodeView::step).toList();
        assertThat(steps).extracting(NodeView::field).containsExactly("loadLatest", "persist(event)", "rebuildSnapshots");
        assertThat(steps).allSatisfy(step -> assertThat(step.parent()).isEqualTo(event.id()));
        assertThat(steps.get(1).ret()).startsWith("PROF");
        assertThat(steps.get(1).sql()).anySatisfy(sql -> assertThat(sql).startsWithIgnoringCase("insert"));
        assertThat(steps.get(2).ret()).matches("\\d+ Snapshots?");
        assertThat(trace.marks()).extracting(mark -> mark.label()).containsExactly("E1", "1", "2", "3");
    }

    @Test
    void blockedDeleteIsDeclinedWithoutWriting() {
        graphql(example("DeleteProfile"));
        TraceView trace = latest();

        assertThat(trace.status()).isEqualTo("declined");
        assertThat(trace.errorCount()).isZero();
        List<NodeView> steps = trace.nodes().stream().filter(NodeView::step).toList();
        assertThat(steps).extracting(NodeView::field).containsExactly("loadLatest", "findReverse", "find");
        assertThat(steps.get(1).items()).isPositive();
        assertThat(steps).flatExtracting(NodeView::sql)
                .allSatisfy(sql -> assertThat(sql).startsWithIgnoringCase("select"));
    }

    // ------------------------------------------------------------------ Auslieferung

    @Test
    void finishedRequestsAreStreamedAsServerSentEvents() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/inspector/stream"))
                    .header("Accept", "text/event-stream")
                    .build();
            HttpResponse<Stream<String>> response = http.send(request, HttpResponse.BodyHandlers.ofLines());
            assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                    type -> assertThat(type).startsWith("text/event-stream"));
            BlockingQueue<String> lines = new LinkedBlockingQueue<>();
            Thread reader = Thread.ofVirtual().start(() -> {
                try {
                    response.body().forEach(lines::add);
                } catch (RuntimeException ignored) {
                    // Verbindung beim Testende geschlossen
                }
            });
            assertThat(awaitLine(lines, "event:hello")).isNotNull();

            graphql(example("ProfileStatus"));

            assertThat(awaitLine(lines, "event:start")).isNotNull();
            assertThat(awaitLine(lines, "event:trace")).isNotNull();
            String data = lines.poll(5, TimeUnit.SECONDS);
            assertThat(data).startsWith("data:").contains("\"operation\":\"ProfileStatus\"").contains("\"nodes\":[");
            reader.interrupt();
        } finally {
            http.shutdownNow();
        }
    }

    @Test
    void introspectionIsNotPublished() {
        graphql("{ __schema { queryType { name } } }");
        assertThat(hub.recent()).isEmpty();
    }

    @Test
    void rejectedRequestsArePublishedWithoutFields() {
        graphql("query Broken { matchLatestVersions { ");
        TraceView trace = latest();
        assertThat(trace.operation()).isEqualTo("Broken");
        assertThat(trace.status()).isEqualTo("rejected");
        assertThat(trace.nodes()).hasSize(1);
    }

    @Test
    void restApiListsAndReturnsTraces() {
        graphql(example("ProfileStatus"));
        long id = latest().id();
        List<Map<String, Object>> summaries = client.get().uri("/inspector/api/traces")
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        assertThat(summaries).first().satisfies(summary -> {
            assertThat(summary).containsEntry("operation", "ProfileStatus");
            assertThat(summary).containsEntry("status", "ok");
        });
        Map<String, Object> trace = client.get().uri("/inspector/api/traces/{id}", id)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        assertThat(trace).containsEntry("operation", "ProfileStatus").containsKeys("nodes", "spans", "marks", "response");
    }

    @Test
    void pageAndFragmentsAreRenderedWithThymeleaf() {
        graphql(example("TrustBadge"));
        TraceView trace = latest();
        NodeView trust = node(trace, "/matchLatestVersions/profileA/trust");

        String page = client.get().uri("/inspector").retrieve().body(String.class);
        assertThat(page).contains("Call Tree", "Timeline", "Trust-Service ausfallen lassen", "data-example=\"MatchCard\"",
                "data-trace=\"" + trace.id() + "\"", "inspector/app.js", "inspector/graphql-logo-white.svg");
        String logo = client.get().uri("/inspector/graphql-logo-white.svg").retrieve().body(String.class);
        assertThat(logo).startsWith("<svg").contains("viewBox=\"0 0 100 100\"");

        String list = client.get().uri("/inspector/fragments/list").retrieve().body(String.class);
        assertThat(list).contains("id=\"req-list\"", "TrustBadge");

        String details = client.get().uri("/inspector/fragments/node?trace={t}&node={n}", trace.id(), trust.id())
                .retrieve().body(String.class);
        assertThat(details).contains("Profile.trust", "Aufrufkette", "remote:Profile#trust", "RemoteCalls.awaitAndComplete()");
    }

    // ------------------------------------------------------------------ Hilfen

    private Map<String, Object> graphql(String query) {
        return client.post().uri("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("query", query))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
    }

    private static String example(String name) {
        return InspectorExamples.ALL.stream().filter(example -> example.name().equals(name)).findFirst()
                .orElseThrow().query();
    }

    /** Der zuletzt fertige Mitschnitt - die Hub verteilt erst nach dem Ende des Requests. */
    private TraceView latest() {
        assertThat(hub.recent()).isNotEmpty();
        return hub.recent().getFirst();
    }

    private static NodeView node(TraceView trace, String path) {
        return trace.nodes().stream().filter(node -> path.equals(node.path()) && !node.aux() && !node.step())
                .findFirst()
                .orElseThrow(() -> new AssertionError("Kein Knoten " + path + " in " + trace.nodes()));
    }

    private static List<SpanView> spans(TraceView trace, String lane) {
        return trace.spans().stream().filter(span -> lane.equals(span.lane())).toList();
    }

    private static String awaitLine(BlockingQueue<String> lines, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            String line = lines.poll(200, TimeUnit.MILLISECONDS);
            if (expected.equals(line)) {
                return line;
            }
        }
        return null;
    }
}
