package com.bjarne.genericgraphql;

import static org.assertj.core.api.Assertions.assertThat;

import com.bjarne.genericgraphql.remote.SimulatedRemoteService;
import com.bjarne.genericgraphql.remote.presence.SimulatedPresenceService;
import com.bjarne.genericgraphql.remote.trust.SimulatedTrustService;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Prueft das Szenario "Orchestrierungsschicht ueber Microservices" aus GQL2-MICROSERVICES.md:
 * Profile.trust kommt aus dem (simulierten) Trust-Service. Die Queries werden woertlich aus
 * der Doku gelesen.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:remote-fields;DB_CLOSE_DELAY=-1",
                // lang genug, dass sich parallele Aufrufe sicher ueberlappen
                "remote.trust.latency-ms=150",
                "remote.presence.latency-ms=100"})
class RemoteFieldIntegrationTest {

    private static final Path MICROSERVICES_DOC = Path.of("docs/GQL2-MICROSERVICES.md");

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private SimulatedTrustService trustService;

    @Autowired
    private SimulatedPresenceService presenceService;

    @Autowired
    private GraphQLSchema schema;

    private RestClient client;
    private Map<String, String> doc;

    @BeforeEach
    void setUp() throws IOException {
        client = RestClient.create("http://localhost:" + port);
        doc = DocumentedExamples.extract(Files.readString(MICROSERVICES_DOC));
        trustService.reset();
        presenceService.reset();
    }

    @AfterEach
    void tearDown() {
        trustService.reset();
        presenceService.reset();
    }

    @Test
    void schemaOffersTrustForSelectionOnly() {
        GraphQLFieldDefinition trust = schema.getObjectType("Profile").getFieldDefinition("trust");
        assertThat(trust).isNotNull();
        assertThat(trust.getDescription()).contains("Trust-Service", "Nicht filterbar");

        assertThat(((GraphQLInputObjectType) schema.getType("ProfileFilter")).getFieldDefinition("trust")).isNull();
        assertThat(((GraphQLInputObjectType) schema.getType("ProfileOrderBy")).getFieldDefinition("trust")).isNull();
        assertThat(((GraphQLInputObjectType) schema.getType("ProfileInput")).getFieldDefinition("trust")).isNull();

        GraphQLObjectType trustInfo = schema.getObjectType("TrustInfo");
        assertThat(trustInfo.getFieldDefinitions()).extracting(GraphQLFieldDefinition::getName)
                .containsExactlyInAnyOrder("level", "score", "openReports", "lastReviewedOn", "note");

        assertThat(schema.getObjectType("Profile").getFieldDefinition("presence").getDescription())
                .contains("Presence-Service");
        assertThat(((GraphQLInputObjectType) schema.getType("ProfileFilter")).getFieldDefinition("presence")).isNull();
    }

    @Test
    void remoteFieldsOnTheSameLevelRunInParallel() {
        List<Object> profiles = asList(data(example("ProfileStatus")).get("profileLatestVersions"));

        assertThat(profiles).extracting(row -> asMap(row).get("displayName"))
                .containsExactly("Jonas", "Mara", "Sam");
        assertThat(profiles).extracting(row -> get(row, "trust").get("level"))
                .containsExactly("MEDIUM", "HIGH", "LOW");
        assertThat(profiles).extracting(row -> get(row, "presence").get("status"))
                .containsExactly("AWAY", "ONLINE", "OFFLINE");

        // Je Service ein Aufruf - und beide waren gleichzeitig unterwegs. Nacheinander
        // ausgefuehrt waere der Trust-Aufruf beendet, bevor der Presence-Aufruf beginnt.
        assertThat(trustService.calls()).singleElement().satisfies(trust ->
                assertThat(presenceService.calls()).singleElement().satisfies(presence ->
                        assertThat(trust.overlaps(presence))
                                .as("Trust- und Presence-Aufruf ueberlappen sich")
                                .isTrue()));
    }

    @Test
    void outageOfOneServiceDoesNotAffectTheOther() {
        trustService.available(false);

        Map<String, Object> response = post(example("ProfileStatus"));

        assertThat(asList(get(response, "data").get("profileLatestVersions")))
                .allSatisfy(row -> {
                    assertThat(asMap(row)).containsEntry("trust", null);
                    assertThat(get(row, "presence")).containsKey("status");
                });
        assertThat(errorMessages(response)).hasSize(3)
                .allSatisfy(message -> assertThat(message.toString()).contains("Trust-Service nicht erreichbar"));
    }

    @Test
    void trustBadgeLoadsAllProfilesWithOneRemoteCall() {
        List<Object> matches = asList(data(example("TrustBadge")).get("matchLatestVersions"));

        // order: { matchedAt: DESC } - das juengere Match mit Sam zuerst.
        assertThat(matches).extracting(row -> get(get(row, "profileA"), "trust").get("level"))
                .containsExactly("HIGH", "HIGH");
        assertThat(matches).extracting(row -> get(row, "profileB").get("displayName"))
                .containsExactly("Sam", "Jonas");
        assertThat(get(get(matches.getFirst(), "profileB"), "trust"))
                .containsEntry("level", "LOW")
                .containsEntry("openReports", 2);
        assertThat(get(get(matches.get(1), "profileB"), "trust")).containsEntry("level", "MEDIUM");

        // Mara steckt in beiden Matches - trotzdem ein Aufruf mit drei IDs.
        assertThat(callIds(trustService)).containsExactly(Set.of("PROF000001", "PROF000003", "PROF000005"));
    }

    @Test
    void filteringOnTrustIsRejectedBeforeAnyServiceIsCalled() {
        Map<String, Object> response = post(example("TrustFilter"));

        assertThat(response.get("data")).isNull();
        assertThat(errorMessages(response)).singleElement().asString()
                .contains("ProfileFilter", "'trust'");
        assertThat(trustService.calls()).isEmpty();
    }

    @Test
    void wildcardDoesNotSearchRemoteFields() {
        assertThat(asList(data(example("TrustWildcard")).get("profileLatestVersions"))).isEmpty();
    }

    @Test
    void remoteDataIgnoresTheQueryDate() {
        Map<String, Object> data = data(example("TrustHistory"));

        assertThat(get(data, "earlier")).containsEntry("city", "Hamburg").containsEntry("verifiedAt", null);
        assertThat(get(get(data, "earlier"), "trust")).containsEntry("level", "HIGH");
        assertThat(get(data, "today")).containsEntry("city", "Leipzig");
        assertThat(get(get(data, "today"), "trust")).containsEntry("level", "HIGH");

        // Beide Stichtage, derselbe Schluessel - der DataLoader fragt einmal.
        assertThat(callIds(trustService)).containsExactly(Set.of("PROF000001"));
    }

    @Test
    void outageYieldsPartialResult() {
        trustService.available(false);

        Map<String, Object> response = post(example("ProfileCard"));

        Map<String, Object> profile = get(response.get("data"), "profileById");
        assertThat(profile).containsEntry("displayName", "Mara").containsEntry("city", "Leipzig");
        assertThat(asList(profile.get("photos"))).hasSize(2);
        assertThat(profile).containsEntry("trust", null);

        assertThat(asList(response.get("errors"))).singleElement().satisfies(error -> {
            assertThat(asMap(error).get("message").toString())
                    .contains("Trust-Service nicht erreichbar: 503 Service Unavailable");
            assertThat(asMap(error).get("path")).isEqualTo(List.of("profileById", "trust"));
        });
    }

    // -------------------------------------------------------------------------

    private String example(String name) {
        assertThat(doc).as("dokumentiertes Beispiel").containsKey(name);
        return doc.get(name);
    }

    private Map<String, Object> post(String query) {
        return client.post()
                .uri("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("query", query))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
    }

    private Map<String, Object> data(String query) {
        Map<String, Object> response = post(query);
        assertThat(response.get("errors")).as("GraphQL-Fehler").isNull();
        return asMap(response.get("data"));
    }

    private static List<Set<String>> callIds(SimulatedRemoteService<?> service) {
        return service.calls().stream().map(SimulatedRemoteService.Call::ids).toList();
    }

    private static List<Object> errorMessages(Map<String, Object> response) {
        return asList(response.get("errors")).stream().map(error -> asMap(error).get("message")).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return (List<Object>) value;
    }

    private static Map<String, Object> get(Object parent, String key) {
        return asMap(asMap(parent).get(key));
    }
}
