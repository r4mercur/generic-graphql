package com.bjarne.genericgraphql.engine.types;

import com.bjarne.genericgraphql.domain.EventType;
import com.bjarne.genericgraphql.engine.meta.EntityMeta;
import com.bjarne.genericgraphql.engine.meta.MetaProvider;
import com.bjarne.genericgraphql.engine.registry.GqlEntityRegistration;
import com.bjarne.genericgraphql.engine.registry.TypeFieldType;
import graphql.Scalars;
import graphql.scalars.ExtendedScalars;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeReference;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class GqlTypeFactory {

    public static final GraphQLEnumType EVENT_TYPE = GraphQLEnumType.newEnum()
            .name("EventType")
            .value("create", EventType.CREATE)
            .value("update", EventType.UPDATE)
            .value("correct", EventType.CORRECT)
            .value("clear", EventType.CLEAR)
            .build();

    private final MetaProvider metaProvider;
    private final ScalarMapping scalars = new ScalarMapping();

    public GqlTypeFactory(MetaProvider metaProvider) {
        this.metaProvider = metaProvider;
    }

    public ScalarMapping scalars() {
        return scalars;
    }

    // -------------------------------------------------------------------------
    // Objekttyp
    // -------------------------------------------------------------------------

    public GraphQLObjectType buildObjectType(EntityMeta meta) {
        GqlEntityRegistration registration = meta.registration();
        GraphQLObjectType.Builder builder = GraphQLObjectType.newObject()
                .name(registration.typeName())
                .description("Generiert aus dem JPA-Metamodel von " + meta.entityClass().getSimpleName());

        for (EntityMeta.BasicAttr attr : meta.basicAttributes()) {
            if (!registration.includes(attr.name(), TypeFieldType.TYPE)) {
                continue;
            }
            GraphQLType type = scalars.graphQLTypeFor(attr.javaType());
            if (type == null) {
                continue;
            }
            builder.field(GraphQLFieldDefinition.newFieldDefinition()
                    .name(attr.name())
                    .type((GraphQLOutputType) type)
                    .build());
        }

        for (EntityMeta.ForwardRel rel : meta.forwardRelations()) {
            if (!registration.includes(rel.name(), TypeFieldType.TYPE)) {
                continue;
            }
            GqlEntityRegistration target = metaProvider.meta(rel.targetClass()).registration();
            builder.field(GraphQLFieldDefinition.newFieldDefinition()
                    .name(rel.name())
                    .type(GraphQLTypeReference.typeRef(target.typeName()))
                    .description("Wird per DataLoader aufgeloest (gebatcht, kein N+1).")
                    .build());
        }

        for (EntityMeta.ReverseRel rel : meta.reverseRelations()) {
            if (!registration.includes(rel.name(), TypeFieldType.TYPE)) {
                continue;
            }
            GqlEntityRegistration target = metaProvider.meta(rel.targetClass()).registration();
            builder.field(GraphQLFieldDefinition.newFieldDefinition()
                    .name(rel.name())
                    .type(GraphQLList.list(GraphQLTypeReference.typeRef(target.typeName())))
                    .description("Wird per DataLoader aufgeloest (gebatcht, kein N+1).")
                    .build());
        }

        return builder.build();
    }

    // -------------------------------------------------------------------------
    // Filter-Input
    // -------------------------------------------------------------------------

    public GraphQLInputObjectType buildFilterType(EntityMeta meta) {
        GqlEntityRegistration registration = meta.registration();
        String name = registration.filterName();

        GraphQLInputObjectType.Builder builder = GraphQLInputObjectType.newInputObject().name(name);

        for (EntityMeta.BasicAttr attr : meta.basicAttributes()) {
            if (!registration.includes(attr.name(), TypeFieldType.FILTER)) {
                continue;
            }
            GraphQLInputType lookup = LookupTypes.lookupFor(attr.javaType(), scalars);
            if (lookup == null) {
                continue;
            }
            builder.field(inputField(attr.name(), lookup));
        }

        for (EntityMeta.ForwardRel rel : meta.forwardRelations()) {
            if (!registration.includes(rel.name(), TypeFieldType.FILTER)) {
                continue;
            }
            String targetFilter = metaProvider.meta(rel.targetClass()).registration().filterName();
            builder.field(inputField(rel.name(), GraphQLTypeReference.typeRef(targetFilter)));
        }
        for (EntityMeta.ReverseRel rel : meta.reverseRelations()) {
            if (!registration.includes(rel.name(), TypeFieldType.FILTER)) {
                continue;
            }
            String targetFilter = metaProvider.meta(rel.targetClass()).registration().filterName();
            builder.field(inputField(rel.name(), GraphQLTypeReference.typeRef(targetFilter)));
        }

        builder.field(inputField(LookupTypes.AND, GraphQLTypeReference.typeRef(name)));
        builder.field(inputField(LookupTypes.OR, GraphQLTypeReference.typeRef(name)));
        builder.field(inputField(LookupTypes.NOT, GraphQLTypeReference.typeRef(name)));
        builder.field(inputField(LookupTypes.WILDCARD, LookupTypes.WILDCARD_LOOKUP));

        return builder.build();
    }

    // -------------------------------------------------------------------------
    // OrderBy-Input
    // -------------------------------------------------------------------------

    public GraphQLInputObjectType buildOrderByType(EntityMeta meta) {
        GqlEntityRegistration registration = meta.registration();
        GraphQLInputObjectType.Builder builder = GraphQLInputObjectType.newInputObject()
                .name(registration.orderByName());

        for (EntityMeta.BasicAttr attr : meta.basicAttributes()) {
            if (!registration.includes(attr.name(), TypeFieldType.ORDER_BY)) {
                continue;
            }
            if (!ScalarMapping.isSupported(attr.javaType())) {
                continue;
            }
            builder.field(inputField(attr.name(), LookupTypes.ORDER_DIRECTION));
        }

        for (EntityMeta.ForwardRel rel : meta.forwardRelations()) {
            if (!registration.includes(rel.name(), TypeFieldType.ORDER_BY)) {
                continue;
            }
            String targetOrderBy = metaProvider.meta(rel.targetClass()).registration().orderByName();
            builder.field(inputField(rel.name(), GraphQLTypeReference.typeRef(targetOrderBy)));
        }

        return builder.build();
    }

    // -------------------------------------------------------------------------
    // Mutation-Input
    // -------------------------------------------------------------------------

    public GraphQLInputObjectType buildInputType(EntityMeta meta) {
        GqlEntityRegistration registration = meta.registration();
        GraphQLInputObjectType.Builder builder = GraphQLInputObjectType.newInputObject()
                .name(registration.inputName());

        for (EntityMeta.BasicAttr attr : meta.basicAttributes()) {
            if (!registration.includes(attr.name(), TypeFieldType.INPUT)) {
                continue;
            }
            // Das event-Feld bekommt statt String das EventType-Enum.
            if ("event".equals(attr.name())) {
                builder.field(inputField("event", EVENT_TYPE));
                continue;
            }
            GraphQLType type = scalars.graphQLTypeFor(attr.javaType());
            if (type == null) {
                continue;
            }
            builder.field(inputField(attr.name(), (GraphQLInputType) type));
        }

        for (EntityMeta.ForwardRel rel : meta.forwardRelations()) {
            if (!registration.includes(rel.name(), TypeFieldType.INPUT)) {
                continue;
            }
            builder.field(GraphQLInputObjectField.newInputObjectField()
                    .name(rel.name())
                    .type(Scalars.GraphQLString)
                    .description("Fachliche ID (" + rel.targetIdAttribute() + ") des Zielobjekts")
                    .build());
        }

        if (registration.eventSourced()) {
            builder.field(GraphQLInputObjectField.newInputObjectField()
                    .name("clearedFieldName")
                    .type(Scalars.GraphQLString)
                    .description("Nur fuer event=clear: Name des Feldes, das geleert werden soll.")
                    .build());
        }

        return builder.build();
    }

    // -------------------------------------------------------------------------

    public Map<String, GraphQLEnumType> collectedEnums() {
        Map<String, GraphQLEnumType> result = new LinkedHashMap<>(scalars.enumTypes());
        result.put(EVENT_TYPE.getName(), EVENT_TYPE);
        result.put(LookupTypes.ORDER_DIRECTION.getName(), LookupTypes.ORDER_DIRECTION);
        return result;
    }

    public static GraphQLInputObjectField inputField(String name, GraphQLInputType type) {
        return GraphQLInputObjectField.newInputObjectField().name(name).type(type).build();
    }

    public static GraphQLOutputType dateScalar() {
        return ExtendedScalars.Date;
    }
}
