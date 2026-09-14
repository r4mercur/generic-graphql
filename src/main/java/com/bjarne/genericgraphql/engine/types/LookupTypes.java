package com.bjarne.genericgraphql.engine.types;

import graphql.Scalars;
import graphql.scalars.ExtendedScalars;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLScalarType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LookupTypes {

    // --- Feldnamen, die die FilterEngine kennt --------------------------------

    public static final String EXACT = "exact";
    public static final String I_EXACT = "iExact";
    public static final String CONTAINS = "contains";
    public static final String I_CONTAINS = "iContains";
    public static final String STARTS_WITH = "startsWith";
    public static final String I_STARTS_WITH = "iStartsWith";
    public static final String ENDS_WITH = "endsWith";
    public static final String I_ENDS_WITH = "iEndsWith";
    public static final String IN_LIST = "inList";
    public static final String IS_NULL = "isNull";
    public static final String GT = "gt";
    public static final String GTE = "gte";
    public static final String GT_OR_NULL = "gtOrNull";
    public static final String LT = "lt";
    public static final String LTE = "lte";
    public static final String LT_OR_NULL = "ltOrNull";
    public static final String RANGE = "range";
    public static final String IS_TRUE = "isTrue";
    public static final String IS_FALSE = "isFalse";
    public static final String IS_TRUE_OR_NULL = "isTrueOrNull";
    public static final String IS_FALSE_OR_NULL = "isFalseOrNull";

    // --- Sonderfelder im Filter-Typ ------------------------------------------

    public static final String AND = "AND";
    public static final String OR = "OR";
    public static final String NOT = "NOT";
    public static final String WILDCARD = "WILDCARDFILTER";

    // --- Die Lookup-Typen selbst ---------------------------------------------

    public static final GraphQLInputObjectType STRING_LOOKUP = stringLookup();
    public static final GraphQLInputObjectType BOOLEAN_LOOKUP = booleanLookup();
    public static final GraphQLInputObjectType INT_LOOKUP =
            comparableLookup("IntFilterLookup", Scalars.GraphQLInt);
    public static final GraphQLInputObjectType LONG_LOOKUP =
            comparableLookup("LongFilterLookup", ExtendedScalars.GraphQLLong);
    public static final GraphQLInputObjectType BIG_DECIMAL_LOOKUP =
            comparableLookup("DecimalFilterLookup", ExtendedScalars.GraphQLBigDecimal);
    public static final GraphQLInputObjectType FLOAT_LOOKUP =
            comparableLookup("FloatFilterLookup", Scalars.GraphQLFloat);
    public static final GraphQLInputObjectType DATE_LOOKUP =
            comparableLookup("DateFilterLookup", ExtendedScalars.Date);
    public static final GraphQLInputObjectType DATE_TIME_LOOKUP =
            comparableLookup("DateTimeFilterLookup", ExtendedScalars.DateTime);

    public static final GraphQLInputObjectType WILDCARD_LOOKUP = GraphQLInputObjectType.newInputObject()
            .name("WildcardFilterLookup")
            .description("Legt denselben Text-Lookup ueber alle vom Client angefragten String-Felder (ODER-verknuepft).")
            .field(field(CONTAINS, Scalars.GraphQLString))
            .field(field(I_CONTAINS, Scalars.GraphQLString))
            .field(field(STARTS_WITH, Scalars.GraphQLString))
            .field(field(I_STARTS_WITH, Scalars.GraphQLString))
            .field(field(ENDS_WITH, Scalars.GraphQLString))
            .field(field(I_ENDS_WITH, Scalars.GraphQLString))
            .field(field(EXACT, Scalars.GraphQLString))
            .field(field(I_EXACT, Scalars.GraphQLString))
            .build();

    public static final GraphQLEnumType ORDER_DIRECTION = GraphQLEnumType.newEnum()
            .name("OrderDirection")
            .value("ASC")
            .value("DESC")
            .build();

    private static final Map<Class<?>, GraphQLInputObjectType> BY_JAVA_TYPE = new LinkedHashMap<>();

    static {
        BY_JAVA_TYPE.put(String.class, STRING_LOOKUP);
        BY_JAVA_TYPE.put(Character.class, STRING_LOOKUP);
        BY_JAVA_TYPE.put(Boolean.class, BOOLEAN_LOOKUP);
        BY_JAVA_TYPE.put(Integer.class, INT_LOOKUP);
        BY_JAVA_TYPE.put(Short.class, INT_LOOKUP);
        BY_JAVA_TYPE.put(Byte.class, INT_LOOKUP);
        BY_JAVA_TYPE.put(Long.class, LONG_LOOKUP);
        BY_JAVA_TYPE.put(BigInteger.class, LONG_LOOKUP);
        BY_JAVA_TYPE.put(BigDecimal.class, BIG_DECIMAL_LOOKUP);
        BY_JAVA_TYPE.put(Double.class, FLOAT_LOOKUP);
        BY_JAVA_TYPE.put(Float.class, FLOAT_LOOKUP);
        BY_JAVA_TYPE.put(LocalDate.class, DATE_LOOKUP);
        BY_JAVA_TYPE.put(OffsetDateTime.class, DATE_TIME_LOOKUP);
    }

    private LookupTypes() {
    }

    public static GraphQLInputType lookupFor(Class<?> javaType, ScalarMapping scalars) {
        if (javaType.isEnum()) {
            return enumLookup(scalars.enumType(javaType));
        }
        return BY_JAVA_TYPE.get(javaType);
    }

    public static boolean isFilterable(Class<?> javaType) {
        return javaType.isEnum() || BY_JAVA_TYPE.containsKey(javaType);
    }

    public static List<GraphQLInputObjectType> all() {
        List<GraphQLInputObjectType> types = new ArrayList<>();
        types.add(STRING_LOOKUP);
        types.add(BOOLEAN_LOOKUP);
        types.add(INT_LOOKUP);
        types.add(LONG_LOOKUP);
        types.add(BIG_DECIMAL_LOOKUP);
        types.add(FLOAT_LOOKUP);
        types.add(DATE_LOOKUP);
        types.add(DATE_TIME_LOOKUP);
        types.add(WILDCARD_LOOKUP);
        return types;
    }

    private static final Map<String, GraphQLInputObjectType> ENUM_LOOKUPS = new LinkedHashMap<>();

    private static GraphQLInputObjectType enumLookup(GraphQLEnumType enumType) {
        return ENUM_LOOKUPS.computeIfAbsent(enumType.getName(), name -> GraphQLInputObjectType.newInputObject()
                .name(name + "FilterLookup")
                .field(field(EXACT, enumType))
                .field(listField(IN_LIST, enumType))
                .field(field(IS_NULL, Scalars.GraphQLBoolean))
                .build());
    }

    private static GraphQLInputObjectType stringLookup() {
        return GraphQLInputObjectType.newInputObject()
                .name("StringFilterLookup")
                .field(field(EXACT, Scalars.GraphQLString))
                .field(field(I_EXACT, Scalars.GraphQLString))
                .field(field(CONTAINS, Scalars.GraphQLString))
                .field(field(I_CONTAINS, Scalars.GraphQLString))
                .field(field(STARTS_WITH, Scalars.GraphQLString))
                .field(field(I_STARTS_WITH, Scalars.GraphQLString))
                .field(field(ENDS_WITH, Scalars.GraphQLString))
                .field(field(I_ENDS_WITH, Scalars.GraphQLString))
                .field(listField(IN_LIST, Scalars.GraphQLString))
                .field(field(IS_NULL, Scalars.GraphQLBoolean))
                .build();
    }

    private static GraphQLInputObjectType booleanLookup() {
        return GraphQLInputObjectType.newInputObject()
                .name("BooleanFilterLookup")
                .field(field(EXACT, Scalars.GraphQLBoolean))
                .field(field(IS_TRUE, Scalars.GraphQLBoolean))
                .field(field(IS_FALSE, Scalars.GraphQLBoolean))
                .field(field(IS_TRUE_OR_NULL, Scalars.GraphQLBoolean))
                .field(field(IS_FALSE_OR_NULL, Scalars.GraphQLBoolean))
                .field(field(IS_NULL, Scalars.GraphQLBoolean))
                .build();
    }

    private static GraphQLInputObjectType comparableLookup(String name, GraphQLScalarType scalar) {
        return GraphQLInputObjectType.newInputObject()
                .name(name)
                .field(field(EXACT, scalar))
                .field(field(GT, scalar))
                .field(field(GTE, scalar))
                .field(field(GT_OR_NULL, scalar))
                .field(field(LT, scalar))
                .field(field(LTE, scalar))
                .field(field(LT_OR_NULL, scalar))
                .field(listField(RANGE, scalar))
                .field(listField(IN_LIST, scalar))
                .field(field(IS_NULL, Scalars.GraphQLBoolean))
                .build();
    }

    private static GraphQLInputObjectField field(String name, GraphQLInputType type) {
        return GraphQLInputObjectField.newInputObjectField().name(name).type(type).build();
    }

    private static GraphQLInputObjectField listField(String name, GraphQLInputType type) {
        return GraphQLInputObjectField.newInputObjectField()
                .name(name)
                .type(GraphQLList.list(type))
                .build();
    }
}
