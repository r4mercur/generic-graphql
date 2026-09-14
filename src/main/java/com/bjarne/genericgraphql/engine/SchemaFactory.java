package com.bjarne.genericgraphql.engine;

import com.bjarne.genericgraphql.engine.meta.EntityMeta;
import com.bjarne.genericgraphql.engine.meta.MetaProvider;
import com.bjarne.genericgraphql.engine.registry.GqlEntityRegistration;
import com.bjarne.genericgraphql.engine.resolver.GenericDataFetchers;
import com.bjarne.genericgraphql.engine.types.GqlTypeFactory;
import com.bjarne.genericgraphql.engine.types.LookupTypes;
import graphql.Scalars;
import graphql.scalars.ExtendedScalars;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLCodeRegistry;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeReference;
import graphql.schema.PropertyDataFetcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class SchemaFactory {

    private final MetaProvider metaProvider;
    private final GqlTypeFactory typeFactory;
    private final GenericDataFetchers dataFetchers;

    public SchemaFactory(MetaProvider metaProvider,
                         GqlTypeFactory typeFactory,
                         GenericDataFetchers dataFetchers) {
        this.metaProvider = metaProvider;
        this.typeFactory = typeFactory;
        this.dataFetchers = dataFetchers;
    }

    // --- feste Hilfstypen ----------------------------------------------------

    private static final GraphQLObjectType RELATION_REF = GraphQLObjectType.newObject()
            .name("RelationRef")
            .field(f -> f.name("relation").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)))
            .field(f -> f.name("endpoint").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)))
            .field(f -> f.name("id").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)))
            .build();

    private static final GraphQLObjectType RELATIONS = GraphQLObjectType.newObject()
            .name("Relations")
            .description("Beziehungen eines Objekts in beide Richtungen.")
            .field(f -> f.name("forwardRelations").type(GraphQLList.list(GraphQLNonNull.nonNull(RELATION_REF))))
            .field(f -> f.name("reverseRelations").type(GraphQLList.list(GraphQLNonNull.nonNull(RELATION_REF))))
            .build();

    private static final GraphQLObjectType MUTATION_RESULT = GraphQLObjectType.newObject()
            .name("MutationResult")
            .description("Einheitliches Ergebnis jeder Event-Mutation - fuer alle Domaenenobjekte gleich.")
            .field(f -> f.name("result").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)))
            .field(f -> f.name("message").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)))
            .field(f -> f.name("objectBezugsId").type(Scalars.GraphQLString))
            .field(f -> f.name("relations").type(RELATIONS))
            .build();

    // -------------------------------------------------------------------------

    public GraphQLSchema build() {
        List<EntityMeta> metas = metaProvider.allMetas();

        Map<String, GraphQLObjectType> objectTypes = new LinkedHashMap<>();
        Map<String, GraphQLInputObjectType> filterTypes = new LinkedHashMap<>();
        Map<String, GraphQLInputObjectType> orderByTypes = new LinkedHashMap<>();
        Map<String, GraphQLInputObjectType> inputTypes = new LinkedHashMap<>();

        for (EntityMeta meta : metas) {
            GqlEntityRegistration reg = meta.registration();
            objectTypes.put(reg.typeName(), typeFactory.buildObjectType(meta));
            filterTypes.put(reg.filterName(), typeFactory.buildFilterType(meta));
            orderByTypes.put(reg.orderByName(), typeFactory.buildOrderByType(meta));
            inputTypes.put(reg.inputName(), typeFactory.buildInputType(meta));
        }

        GraphQLCodeRegistry.Builder codeRegistry = GraphQLCodeRegistry.newCodeRegistry();
        GraphQLObjectType.Builder query = GraphQLObjectType.newObject().name("Query");
        GraphQLObjectType.Builder mutation = GraphQLObjectType.newObject().name("Mutation");

        for (EntityMeta meta : metas) {
            wireRelationFetchers(meta, codeRegistry);
            addQueryFields(meta, query, codeRegistry);
            addMutationField(meta, mutation, codeRegistry);
        }

        // Die beiden Hilfstypen haben record-Accessoren ohne "get"-Prefix.
        wireRecordAccessors(codeRegistry);

        Set<GraphQLNamedType> additionalTypes = new LinkedHashSet<>();
        additionalTypes.addAll(objectTypes.values());
        additionalTypes.addAll(filterTypes.values());
        additionalTypes.addAll(orderByTypes.values());
        additionalTypes.addAll(inputTypes.values());
        additionalTypes.addAll(LookupTypes.all());
        additionalTypes.addAll(typeFactory.collectedEnums().values());
        additionalTypes.add(RELATION_REF);
        additionalTypes.add(RELATIONS);
        additionalTypes.add(MUTATION_RESULT);

        return GraphQLSchema.newSchema()
                .query(query.build())
                .mutation(mutation.build())
                .additionalTypes(additionalTypes)
                .codeRegistry(codeRegistry.build())
                .build();
    }

    // -------------------------------------------------------------------------

    private void wireRelationFetchers(EntityMeta meta, GraphQLCodeRegistry.Builder codeRegistry) {
        String typeName = meta.registration().typeName();

        for (EntityMeta.ForwardRel rel : meta.forwardRelations()) {
            codeRegistry.dataFetcher(
                    FieldCoordinates.coordinates(typeName, rel.name()),
                    dataFetchers.forward(rel));
        }
        for (EntityMeta.ReverseRel rel : meta.reverseRelations()) {
            codeRegistry.dataFetcher(
                    FieldCoordinates.coordinates(typeName, rel.name()),
                    dataFetchers.reverse(rel));
        }
    }

    private void addQueryFields(EntityMeta meta,
                                GraphQLObjectType.Builder query,
                                GraphQLCodeRegistry.Builder codeRegistry) {

        GqlEntityRegistration reg = meta.registration();
        String endpoint = reg.endpointName();
        String typeRef = reg.typeName();
        String idAttribute = reg.idAttribute();

        // 1) Liste
        String listField = reg.eventSourced() ? endpoint + "LatestVersions" : endpoint + "List";
        GraphQLFieldDefinition.Builder list = GraphQLFieldDefinition.newFieldDefinition()
                .name(listField)
                .type(GraphQLList.list(GraphQLTypeReference.typeRef(typeRef)))
                .argument(arg("limit", Scalars.GraphQLInt, GenericDataFetchers.DEFAULT_LIMIT))
                .argument(arg("offset", Scalars.GraphQLInt, 0))
                .argument(arg("filter", GraphQLTypeReference.typeRef(reg.filterName()), null))
                .argument(arg("order", GraphQLTypeReference.typeRef(reg.orderByName()), null));
        if (reg.eventSourced()) {
            list.argument(arg("date", ExtendedScalars.Date, null))
                    .argument(arg("active", Scalars.GraphQLBoolean, null))
                    .description("Der zum Stichtag gueltige Stand. Ohne date: heute.");
        }
        query.field(list.build());
        codeRegistry.dataFetcher(FieldCoordinates.coordinates("Query", listField), dataFetchers.list(meta));

        // 2) Einzelabruf
        String byIdField = endpoint + "ById";
        GraphQLFieldDefinition.Builder byId = GraphQLFieldDefinition.newFieldDefinition()
                .name(byIdField)
                .type(GraphQLTypeReference.typeRef(typeRef))
                .argument(GraphQLArgument.newArgument()
                        .name(idAttribute)
                        .type(GraphQLNonNull.nonNull(Scalars.GraphQLString))
                        .build());
        if (reg.eventSourced()) {
            byId.argument(arg("date", ExtendedScalars.Date, null))
                    .argument(arg("active", Scalars.GraphQLBoolean, null));
        }
        query.field(byId.build());
        codeRegistry.dataFetcher(FieldCoordinates.coordinates("Query", byIdField), dataFetchers.byObjectId(meta));

        if (!reg.eventSourced()) {
            return;
        }

        // 3) Zeitpunkte - "wann hat sich hier etwas geaendert?"
        String timestampsField = endpoint + "Timestamps";
        query.field(GraphQLFieldDefinition.newFieldDefinition()
                .name(timestampsField)
                .description("Alle Gueltigkeitszeitpunkte des Objekts - Einstieg fuer Verlaufs-Queries.")
                .type(GraphQLList.list(ExtendedScalars.Date))
                .argument(GraphQLArgument.newArgument()
                        .name("objectBezugsId")
                        .type(GraphQLNonNull.nonNull(Scalars.GraphQLString))
                        .build())
                .build());
        codeRegistry.dataFetcher(FieldCoordinates.coordinates("Query", timestampsField), dataFetchers.timestamps(meta));

        // 4) Event-Log
        String logField = endpoint + "Log";
        query.field(GraphQLFieldDefinition.newFieldDefinition()
                .name(logField)
                .description("Rohes Event-Log des Objekts (ohne Snapshots).")
                .type(GraphQLList.list(ExtendedScalars.Json))
                .argument(GraphQLArgument.newArgument()
                        .name("objectBezugsId")
                        .type(GraphQLNonNull.nonNull(Scalars.GraphQLString))
                        .build())
                .build());
        codeRegistry.dataFetcher(FieldCoordinates.coordinates("Query", logField), dataFetchers.log(meta));

        // 5) RelationsFinder
        String relationsField = endpoint + "Relations";
        query.field(GraphQLFieldDefinition.newFieldDefinition()
                .name(relationsField)
                .description("Woran haengt dieses Objekt - und was haengt an ihm?")
                .type(RELATIONS)
                .argument(GraphQLArgument.newArgument()
                        .name(idAttribute)
                        .type(GraphQLNonNull.nonNull(Scalars.GraphQLString))
                        .build())
                .argument(arg("date", ExtendedScalars.Date, null))
                .build());
        codeRegistry.dataFetcher(FieldCoordinates.coordinates("Query", relationsField),
                dataFetchers.relationsFinder(meta));
    }

    private void addMutationField(EntityMeta meta,
                                  GraphQLObjectType.Builder mutation,
                                  GraphQLCodeRegistry.Builder codeRegistry) {

        GqlEntityRegistration reg = meta.registration();
        if (!reg.eventSourced()) {
            return;
        }

        String name = reg.endpointName() + "Event";
        mutation.field(GraphQLFieldDefinition.newFieldDefinition()
                .name(name)
                .description("Einheitliche Schreiboperation: create / update / correct / clear.")
                .type(GraphQLNonNull.nonNull(MUTATION_RESULT))
                .argument(GraphQLArgument.newArgument()
                        .name("data")
                        .type(GraphQLNonNull.nonNull(GraphQLTypeReference.typeRef(reg.inputName())))
                        .build())
                .build());
        codeRegistry.dataFetcher(FieldCoordinates.coordinates("Mutation", name), dataFetchers.eventMutation(meta));
    }

    private void wireRecordAccessors(GraphQLCodeRegistry.Builder codeRegistry) {
        List<String> mutationFields = List.of("result", "message", "objectBezugsId", "relations");
        for (String field : mutationFields) {
            codeRegistry.dataFetcher(FieldCoordinates.coordinates("MutationResult", field),
                    PropertyDataFetcher.fetching(field));
        }
        for (String field : List.of("forwardRelations", "reverseRelations")) {
            codeRegistry.dataFetcher(FieldCoordinates.coordinates("Relations", field),
                    PropertyDataFetcher.fetching(field));
        }
        for (String field : List.of("relation", "endpoint", "id")) {
            codeRegistry.dataFetcher(FieldCoordinates.coordinates("RelationRef", field),
                    PropertyDataFetcher.fetching(field));
        }
    }

    private static GraphQLArgument arg(String name, GraphQLType type, Object defaultValue) {
        GraphQLArgument.Builder builder = GraphQLArgument.newArgument()
                .name(name)
                .type((graphql.schema.GraphQLInputType) type);
        if (defaultValue != null) {
            builder.defaultValueProgrammatic(defaultValue);
        }
        return builder.build();
    }

    public List<String> generatedTypeNames() {
        List<String> names = new ArrayList<>();
        for (EntityMeta meta : metaProvider.allMetas()) {
            GqlEntityRegistration reg = meta.registration();
            names.add(reg.typeName());
            names.add(reg.filterName());
            names.add(reg.orderByName());
            names.add(reg.inputName());
        }
        return names;
    }
}
