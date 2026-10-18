package com.bjarne.genericgraphql;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.client.RestClient;

/**
 * Prueft, dass die Beispiele aus GQL2-JAVA-IMPLEMENTIERUNG.md und der GraphiQL-Vorlage
 * genau so funktionieren wie beschrieben - ueber HTTP gegen die laufende Anwendung.
 * Die Queries werden woertlich aus der Doku gelesen, nicht kopiert: wer ein Beispiel
 * aendert, aendert damit auch den Test.
 *
 * <p>Die Mutationen werden echt committet. Deshalb laeuft der Test auf einer eigenen
 * H2-Datenbank, die Lese-Beispiele laufen vor den Schreib-Beispielen, und der Kontext
 * wird danach verworfen.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:documented-examples;DB_CLOSE_DELAY=-1")
@DirtiesContext
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DocumentedExamplesIntegrationTest {

    private static final Path IMPLEMENTATION_DOC = Path.of("GQL2-JAVA-IMPLEMENTIERUNG.md");

    private static final String TOO_DEEP = """
            { profileLatestVersions { matchesAsA { messages { sender { matchesAsA { messages {
              sender { matchesAsA { messages { sender { matchesAsA { messages {
              sender { displayName } } } } } } } } } } } } } }
            """;

    @Value("${local.server.port}")
    private int port;

    private RestClient client;
    private Map<String, String> doc;
    private Map<String, String> graphiql;

    @BeforeEach
    void setUp() throws IOException {
        client = RestClient.create("http://localhost:" + port);
        doc = DocumentedExamples.extract(Files.readString(IMPLEMENTATION_DOC));
        graphiql = DocumentedExamples.extract(client.get().uri("/graphiql.html").retrieve().body(String.class));
    }

    // -------------------------------------------------------------------------
    // Alle Beispiele
    // -------------------------------------------------------------------------

    @Test
    @Order(1)
    void everyDocumentedQueryRunsWithoutErrors() {
        Map<String, String> queries = new LinkedHashMap<>();
        doc.forEach((name, query) -> {
            if (query.startsWith("query")) {
                queries.put("Doku/" + name, query);
            }
        });
        graphiql.forEach((name, query) -> queries.put("GraphiQL/" + name, query));
        assertThat(queries).hasSizeGreaterThanOrEqualTo(10);

        SoftAssertions.assertSoftly(softly -> queries.forEach((name, query) ->
                softly.assertThat(post(query).get("errors")).as(name).isNull()));
    }

    // -------------------------------------------------------------------------
    // GQL2-JAVA-IMPLEMENTIERUNG.md - "Was das Beispiel zeigt"
    // -------------------------------------------------------------------------

    @Test
    @Order(2)
    void matchCardLoadsFourRelationLevelsInOneRequest() {
        List<Object> matches = listOf(data(example(doc, "MatchCard")), "matchLatestVersions");

        // order: { matchedAt: DESC } - das juengere Match mit Sam zuerst.
        assertThat(matches).extracting(row -> displayName(get(row, "profileB"))).containsExactly("Sam", "Jonas");

        Map<String, Object> first = asMap(matches.getFirst());
        assertThat(get(get(first, "profileA"), "owner")).isEqualTo(
                Map.of("username", "mara", "email", "mara@example.test"));
        assertThat(asList(get(first, "profileB").get("profileInterests")))
                .extracting(link -> get(link, "interest").get("name"))
                .contains("Klettern");
        assertThat(asList(asMap(matches.get(1)).get("messages")))
                .extracting(message -> displayName(get(message, "sender")))
                .containsExactly("Jonas", "Mara");
    }

    @Test
    @Order(2)
    void sharedInterestFiltersAcrossTwoRelations() {
        assertThat(displayNames(data(example(doc, "SharedInterest"))))
                .containsExactlyInAnyOrder("Mara", "Sam");
    }

    @Test
    @Order(2)
    void matchesWithKeywordFiltersAReverseCollection() {
        assertThat(listOf(data(example(doc, "MatchesWithKeyword")), "matchLatestVersions")).hasSize(1);
    }

    @Test
    @Order(2)
    void combinedAppliesOrAndNot() {
        assertThat(displayNames(data(example(doc, "Combined")))).containsExactly("Jonas", "Mara");
    }

    @Test
    @Order(2)
    void wildcardSearchesOnlySelectedFields() {
        String query = example(doc, "Wildcard");
        assertThat(displayNames(data(query))).containsExactlyInAnyOrder("Mara", "Sam");

        // Die Doku sagt: "ohne diese Zeile: kein Treffer".
        String withoutRelation = query.lines()
                .filter(line -> !line.contains("ohne diese Zeile"))
                .collect(Collectors.joining("\n"));
        assertThat(withoutRelation).isNotEqualTo(query);
        assertThat(displayNames(data(withoutRelation))).isEmpty();
    }

    @Test
    @Order(2)
    void historyShowsEarlierAndCurrentState() {
        Map<String, Object> data = data(example(doc, "History"));

        assertThat(listOf(data, "profileTimestamps")).hasSize(3).first().isNull();
        assertThat(get(data, "earlier")).containsEntry("city", "Hamburg").containsEntry("verifiedAt", null);
        assertThat(get(data, "today")).containsEntry("city", "Leipzig");
        assertThat(get(data, "today").get("verifiedAt")).isNotNull();
        assertThat(listOf(data, "log")).extracting(event -> asMap(event).get("event"))
                .containsExactly("create", "update", "update");
    }

    @Test
    @Order(2)
    void tooDeepQueriesAreRejected() {
        Map<String, Object> response = post(TOO_DEEP);
        assertThat(response).doesNotContainKey("data");
        assertThat(asList(response.get("errors")))
                .extracting(error -> asMap(error).get("message"))
                .containsExactly("maximum query depth exceeded 14 > 12");
    }

    // -------------------------------------------------------------------------
    // GraphiQL-Vorlage (so ausgeliefert, wie der Browser sie bekommt)
    // -------------------------------------------------------------------------

    @Test
    @Order(3)
    void graphiqlExamplesReturnTheDescribedData() {
        assertThat(listOf(data(example(graphiql, "MatchCard")), "matchLatestVersions"))
                .extracting(row -> displayName(get(row, "profileB")))
                .containsExactly("Sam", "Jonas");
        assertThat(displayNames(data(example(graphiql, "SharedInterest")))).containsExactly("Mara");
        assertThat(displayNames(data(example(graphiql, "Wildcard")))).containsExactly("Jonas");

        Map<String, Object> history = data(example(graphiql, "History"));
        assertThat(get(history, "earlier")).containsEntry("city", "Hamburg");
        assertThat(get(history, "today")).containsEntry("city", "Leipzig");
    }

    // -------------------------------------------------------------------------
    // Schreib-Beispiele - laufen zuletzt, weil sie Daten veraendern
    // -------------------------------------------------------------------------

    @Test
    @Order(10)
    void deleteProfileIsBlockedByExistingRelations() {
        Map<String, Object> result = get(data(example(doc, "DeleteProfile")), "profileEvent");

        // Die Reihenfolge der Relationen ist nicht festgelegt - nur der Inhalt.
        assertThat(result).containsEntry("result", "error");
        assertThat(result.get("message").toString())
                .startsWith("Objekt PROF000001 kann nicht geloescht werden, es haengen noch Objekte daran: ")
                .contains("MTCH000001", "MTCH000003", "PHOT000001");
        assertThat(asList(get(result, "relations").get("reverseRelations")))
                .contains(
                        Map.of("relation", "Match", "id", "MTCH000001"),
                        Map.of("relation", "Match", "id", "MTCH000003"),
                        Map.of("relation", "Photo", "id", "PHOT000001"));
    }

    @Test
    @Order(11)
    void createInterestCreatesANewObject() {
        Map<String, Object> result = get(data(example(doc, "CreateInterest")), "interestEvent");
        assertThat(result).containsEntry("result", "ok");
        String id = result.get("objectRefId").toString();

        Map<String, Object> interest = get(data("""
                { interestById(objectRefId: "%s") { name category } }
                """.formatted(id)), "interestById");
        assertThat(interest).containsEntry("name", "Bouldern").containsEntry("category", "OUTDOOR");
    }

    @Test
    @Order(12)
    void updateProfileTakesEffectFromValidFrom() {
        assertThat(get(data(example(doc, "UpdateProfile")), "profileEvent")).containsEntry("result", "ok");

        Map<String, Object> data = data("""
                { before: profileById(objectRefId: "PROF000003", date: "2026-08-31") { city }
                  after:  profileById(objectRefId: "PROF000003", date: "2026-09-01") { city } }
                """);
        assertThat(get(data, "before")).containsEntry("city", "Hamburg");
        assertThat(get(data, "after")).containsEntry("city", "Bremen");
    }

    @Test
    @Order(13)
    void clearBioEmptiesTheFieldFromValidFrom() {
        assertThat(get(data(example(doc, "ClearBio")), "profileEvent")).containsEntry("result", "ok");

        Map<String, Object> data = data("""
                { before: profileById(objectRefId: "PROF000005", date: "2026-09-09") { bio }
                  after:  profileById(objectRefId: "PROF000005", date: "2026-09-10") { bio } }
                """);
        assertThat(get(data, "before").get("bio")).isNotNull();
        assertThat(get(data, "after")).containsEntry("bio", null);
    }

    // -------------------------------------------------------------------------

    private static String example(Map<String, String> examples, String name) {
        assertThat(examples).as("dokumentiertes Beispiel").containsKey(name);
        return examples.get(name);
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

    private static List<Object> listOf(Map<String, Object> data, String key) {
        return asList(data.get(key));
    }

    private static String displayName(Map<String, Object> profile) {
        return (String) profile.get("displayName");
    }

    private static List<String> displayNames(Map<String, Object> data) {
        return listOf(data, "profileLatestVersions").stream().map(row -> displayName(asMap(row))).toList();
    }
}
