package com.bjarne.genericgraphql.engine.types;

import graphql.Scalars;
import graphql.scalars.ExtendedScalars;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class ScalarMapping {

    private static final Map<Class<?>, GraphQLScalarType> SCALARS = new LinkedHashMap<>();

    static {
        SCALARS.put(String.class, Scalars.GraphQLString);
        SCALARS.put(Character.class, Scalars.GraphQLString);
        SCALARS.put(Boolean.class, Scalars.GraphQLBoolean);
        SCALARS.put(Integer.class, Scalars.GraphQLInt);
        SCALARS.put(Short.class, Scalars.GraphQLInt);
        SCALARS.put(Byte.class, Scalars.GraphQLInt);
        SCALARS.put(Long.class, ExtendedScalars.GraphQLLong);
        SCALARS.put(BigInteger.class, ExtendedScalars.GraphQLBigInteger);
        SCALARS.put(BigDecimal.class, ExtendedScalars.GraphQLBigDecimal);
        SCALARS.put(Double.class, Scalars.GraphQLFloat);
        SCALARS.put(Float.class, Scalars.GraphQLFloat);
        SCALARS.put(LocalDate.class, ExtendedScalars.Date);
        SCALARS.put(OffsetDateTime.class, ExtendedScalars.DateTime);
        SCALARS.put(ZonedDateTime.class, ExtendedScalars.DateTime);
        SCALARS.put(LocalDateTime.class, ExtendedScalars.LocalTime);
        SCALARS.put(UUID.class, ExtendedScalars.UUID);
    }

    private final Map<String, GraphQLEnumType> enumTypes = new LinkedHashMap<>();

    public GraphQLType graphQLTypeFor(Class<?> javaType) {
        if (javaType.isEnum()) {
            return enumType(javaType);
        }
        return SCALARS.get(javaType);
    }

    public static GraphQLScalarType scalarFor(Class<?> javaType) {
        return SCALARS.get(javaType);
    }

    public GraphQLEnumType enumType(Class<?> enumClass) {
        String name = enumClass.getSimpleName();
        return enumTypes.computeIfAbsent(name, key -> {
            GraphQLEnumType.Builder builder = GraphQLEnumType.newEnum().name(key);
            for (Object constant : enumClass.getEnumConstants()) {
                builder.value(((Enum<?>) constant).name(), constant);
            }
            return builder.build();
        });
    }

    public Map<String, GraphQLEnumType> enumTypes() {
        return enumTypes;
    }

    public static boolean isSupported(Class<?> javaType) {
        return javaType.isEnum() || SCALARS.containsKey(javaType);
    }
}
