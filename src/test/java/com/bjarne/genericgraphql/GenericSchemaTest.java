package com.bjarne.genericgraphql;

import static org.assertj.core.api.Assertions.assertThat;

import com.bjarne.genericgraphql.engine.dataloader.RelationDataLoaders;
import com.bjarne.genericgraphql.engine.query.RequestState;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.dataloader.DataLoaderRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class GenericSchemaTest {

    @Autowired
    private GraphQL graphQL;

    @Autowired
    private GraphQLSchema schema;

    @Autowired
    private RelationDataLoaders dataLoaders;

    // -------------------------------------------------------------------------
    // Typgenerierung
    // -------------------------------------------------------------------------

    @Test
    void generatesFourTypesPerEntity() {
        for (String entity : List.of("Profile", "Interest", "Photo", "Match", "Message", "ProfileInterest")) {
            assertThat(schema.getType(entity)).as(entity).isNotNull();
            assertThat(schema.getType(entity + "Filter")).as(entity + "Filter").isNotNull();
            assertThat(schema.getType(entity + "OrderBy")).as(entity + "OrderBy").isNotNull();
            assertThat(schema.getType(entity + "Input")).as(entity + "Input").isNotNull();
        }
    }

    @Test
    void filterTypeHasBooleanOperatorsAndWildcard() {
        GraphQLInputObjectType filter = (GraphQLInputObjectType) schema.getType("ProfileFilter");
        assertThat(filter.getFieldDefinition("AND")).isNotNull();
        assertThat(filter.getFieldDefinition("OR")).isNotNull();
        assertThat(filter.getFieldDefinition("NOT")).isNotNull();
        assertThat(filter.getFieldDefinition("WILDCARDFILTER")).isNotNull();
    }

    @Test
    void filterTypeReferencesTargetEntityFilter() {
        GraphQLInputObjectType filter = (GraphQLInputObjectType) schema.getType("ProfileFilter");
        assertThat(filter.getFieldDefinition("profileInterests").getType().toString())
                .contains("ProfileInterestFilter");
        assertThat(filter.getFieldDefinition("owner").getType().toString())
                .contains("AppUserFilter");
    }

    @Test
    void everyEntityGetsQueriesAndMutation() {
        GraphQLObjectType query = schema.getQueryType();
        assertThat(query.getFieldDefinition("profileLatestVersions")).isNotNull();
        assertThat(query.getFieldDefinition("profileById")).isNotNull();
        assertThat(query.getFieldDefinition("profileTimestamps")).isNotNull();
        assertThat(query.getFieldDefinition("profileLog")).isNotNull();
        assertThat(query.getFieldDefinition("profileRelations")).isNotNull();
        assertThat(schema.getMutationType().getFieldDefinition("profileEvent")).isNotNull();

        // Nicht event-gesourcte Entities bekommen keine Historie und keine Mutation.
        assertThat(query.getFieldDefinition("appUserList")).isNotNull();
        assertThat(query.getFieldDefinition("appUserTimestamps")).isNull();
        assertThat(schema.getMutationType().getFieldDefinition("appUserEvent")).isNull();
    }

    // -------------------------------------------------------------------------
    // Filter-Engine
    // -------------------------------------------------------------------------

    @Test
    void filtersAcrossTwoRelations() {
        Map<String, Object> data = execute("""
                { profileLatestVersions(filter: {
                    profileInterests: { interest: { name: { iExact: "Klettern" } } }
                  }) { displayName } }
                """);
        assertThat(names(data, "profileLatestVersions")).containsExactlyInAnyOrder("Mara", "Sam");
    }

    @Test
    void filtersAcrossReverseCollection() {
        Map<String, Object> data = execute("""
                { matchLatestVersions(filter: { messages: { body: { iContains: "hafen" } } })
                  { objectRefId } }
                """);
        assertThat(listOf(data, "matchLatestVersions")).hasSize(1);
    }

    @Test
    void combinesOrAndNot() {
        Map<String, Object> data = execute("""
                { profileLatestVersions(
                    filter: { OR: { city: { iExact: "leipzig" }, heightCm: { gte: 180 } },
                              NOT: { displayName: { exact: "Sam" } } }
                    order: { displayName: ASC }
                  ) { displayName } }
                """);
        assertThat(names(data, "profileLatestVersions")).containsExactly("Jonas", "Mara");
    }

    @Test
    void wildcardSearchesExactlyTheSelectedFields() {
        // "Boulderhalle" steht nur in Maras bio.
        Map<String, Object> withBio = execute("""
                { profileLatestVersions(filter: { WILDCARDFILTER: { iContains: "boulderhalle" } })
                  { displayName bio } }
                """);
        assertThat(names(withBio, "profileLatestVersions")).containsExactly("Mara");

        // Ohne bio in der Selektion gibt es kein Feld mehr, das treffen koennte.
        Map<String, Object> withoutBio = execute("""
                { profileLatestVersions(filter: { WILDCARDFILTER: { iContains: "boulderhalle" } })
                  { displayName } }
                """);
        assertThat(names(withoutBio, "profileLatestVersions")).isEmpty();
    }

    @Test
    void wildcardAlsoCoversSelectedRelations() {
        // "Klettern" steht in keinem Profilfeld, nur im Namen eines Interesses.
        Map<String, Object> withoutRelation = execute("""
                { profileLatestVersions(filter: { WILDCARDFILTER: { iContains: "klettern" } })
                  { displayName bio city } }
                """);
        assertThat(names(withoutRelation, "profileLatestVersions")).isEmpty();

        Map<String, Object> withRelation = execute("""
                { profileLatestVersions(filter: { WILDCARDFILTER: { iContains: "klettern" } })
                  { displayName profileInterests { interest { name } } } }
                """);
        assertThat(names(withRelation, "profileLatestVersions")).containsExactlyInAnyOrder("Mara", "Sam");
    }

    // -------------------------------------------------------------------------
    // Event-Sourcing
    // -------------------------------------------------------------------------

    @Test
    void returnsStateValidAtReferenceDate() {
        Map<String, Object> data = execute("""
                { earlier: profileById(objectRefId: "PROF000001", date: "2025-12-01") { city }
                  later: profileById(objectRefId: "PROF000001") { city } }
                """);
        assertThat(mapOf(data, "earlier")).containsEntry("city", "Hamburg");
        assertThat(mapOf(data, "later")).containsEntry("city", "Leipzig");
    }

    @Test
    void returnsExactlyOneVersionPerObjectRefId() {
        Map<String, Object> data = execute("{ profileLatestVersions { objectRefId } }");
        List<Object> rows = listOf(data, "profileLatestVersions");
        assertThat(rows).extracting(row -> asMap(row).get("objectRefId")).doesNotHaveDuplicates();
    }

    @Test
    void referenceDateAlsoAppliesToLoadedRelations() {
        Map<String, Object> data = execute("""
                { matchLatestVersions(date: "2025-12-01") { profileA { city } } }
                """);
        List<Object> rows = listOf(data, "matchLatestVersions");
        assertThat(rows).isNotEmpty();
        assertThat(asMap(asMap(rows.getFirst()).get("profileA"))).containsEntry("city", "Hamburg");
    }

    // -------------------------------------------------------------------------
    // Mutationen
    // -------------------------------------------------------------------------

    @Test
    void createAndUpdateProduceHistory() {
        Map<String, Object> created = execute("""
                mutation { interestEvent(data: { event: create, name: "Segeln", category: OUTDOOR })
                  { result objectRefId } }
                """);
        Map<String, Object> result = mapOf(created, "interestEvent");
        assertThat(result).containsEntry("result", "ok");
        String id = (String) result.get("objectRefId");

        execute("""
                mutation { interestEvent(data: { event: update, objectRefId: "%s",
                    validFrom: "2026-06-01", description: "Mit Wind und ohne Motor" })
                  { result } }
                """.formatted(id));

        Map<String, Object> after = execute("""
                { before: interestById(objectRefId: "%s", date: "2026-01-01") { description }
                  after: interestById(objectRefId: "%s") { description } }
                """.formatted(id, id));

        assertThat(mapOf(after, "before")).containsEntry("description", null);
        assertThat(mapOf(after, "after")).containsEntry("description", "Mit Wind und ohne Motor");
    }

    @Test
    void clearEmptiesExactlyOneFieldFromDate() {
        Map<String, Object> created = execute("""
                mutation { interestEvent(data: { event: create, name: "Töpfern",
                    category: ARTS, description: "Mit Drehscheibe" }) { objectRefId } }
                """);
        String id = (String) mapOf(created, "interestEvent").get("objectRefId");

        execute("""
                mutation { interestEvent(data: { event: clear, objectRefId: "%s",
                    validFrom: "2026-06-01", clearedFieldName: "description" }) { result } }
                """.formatted(id));

        Map<String, Object> data = execute("""
                { before: interestById(objectRefId: "%s", date: "2026-01-01") { name description }
                  after: interestById(objectRefId: "%s") { name description } }
                """.formatted(id, id));

        assertThat(mapOf(data, "before")).containsEntry("description", "Mit Drehscheibe");
        assertThat(mapOf(data, "after")).containsEntry("description", null);
        // Der Name bleibt - clear trifft genau ein Feld.
        assertThat(mapOf(data, "after")).containsEntry("name", "Töpfern");
    }

    @Test
    void deleteIsBlockedByExistingRelations() {
        Map<String, Object> data = execute("""
                mutation { profileEvent(data: { event: update, objectRefId: "PROF000001",
                    validFrom: "2026-09-12", deleted: true })
                  { result relations { reverseRelations { relation } } } }
                """);
        Map<String, Object> result = mapOf(data, "profileEvent");
        assertThat(result).containsEntry("result", "error");
        assertThat(asMap(result.get("relations"))).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Guardrails
    // -------------------------------------------------------------------------

    private static final String TOO_DEEP = """
            { profileLatestVersions { matchesAsA { messages { sender { matchesAsA { messages {
              sender { matchesAsA { messages { sender { matchesAsA { messages {
              sender { displayName } } } } } } } } } } } } } }
            """;

    @Test
    void limitsNestingDepth() {
        ExecutionResult result = run(TOO_DEEP);
        assertThat(result.getErrors()).isNotEmpty();
        assertThat(result.getErrors().getFirst().getMessage()).contains("depth");
    }

    @Test
    void introspectionIsNotSubjectToDepthLimit() {
        ExecutionResult result = run(INTROSPECTION_QUERY);
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.<Map<String, Object>>getData()).containsKey("__schema");
    }

    @Test
    void introspectionDoesNotBypassLimits() {
        // __typename neben einer zu tiefen Datenabfrage darf nichts freischalten.
        ExecutionResult result = run("{ __typename " + TOO_DEEP.substring(TOO_DEEP.indexOf('{') + 1));
        assertThat(result.getErrors()).isNotEmpty();
        assertThat(result.getErrors().getFirst().getMessage()).contains("depth");
    }

    private static final String INTROSPECTION_QUERY = """
            query IntrospectionQuery {
              __schema {
                queryType { name }
                mutationType { name }
                types { ...FullType }
                directives { name locations args { ...InputValue } }
              }
            }
            fragment FullType on __Type {
              kind name description
              fields(includeDeprecated: true) {
                name args { ...InputValue } type { ...TypeRef } isDeprecated
              }
              inputFields { ...InputValue }
              interfaces { ...TypeRef }
              enumValues(includeDeprecated: true) { name isDeprecated }
              possibleTypes { ...TypeRef }
            }
            fragment InputValue on __InputValue { name type { ...TypeRef } defaultValue }
            fragment TypeRef on __Type {
              kind name
              ofType { kind name ofType { kind name ofType { kind name
                ofType { kind name ofType { kind name ofType { kind name
                ofType { kind name } } } } } } }
            }
            """;

    // -------------------------------------------------------------------------

    private ExecutionResult run(String query) {
        RequestState state = new RequestState();
        DataLoaderRegistry registry = dataLoaders.newRegistry(state);

        Map<String, Object> context = new LinkedHashMap<>();
        context.put(RelationDataLoaders.CONTEXT_KEY, registry);
        context.put(RequestState.CONTEXT_KEY, state);

        return graphQL.execute(ExecutionInput.newExecutionInput()
                .query(query)
                .dataLoaderRegistry(registry)
                .graphQLContext(context)
                .build());
    }

    private Map<String, Object> execute(String query) {
        ExecutionResult result = run(query);
        assertThat(result.getErrors()).as("GraphQL-Fehler").isEmpty();
        return result.getData();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> mapOf(Map<String, Object> data, String key) {
        return asMap(data.get(key));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listOf(Map<String, Object> data, String key) {
        return (List<Object>) data.get(key);
    }

    private static List<String> names(Map<String, Object> data, String key) {
        return listOf(data, key).stream().map(row -> (String) asMap(row).get("displayName")).toList();
    }
}
