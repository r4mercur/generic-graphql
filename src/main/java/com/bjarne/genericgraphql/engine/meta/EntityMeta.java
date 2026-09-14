package com.bjarne.genericgraphql.engine.meta;

import com.bjarne.genericgraphql.engine.registry.GqlEntityRegistration;
import jakarta.persistence.metamodel.EntityType;
import java.util.Collection;
import java.util.Map;

public record EntityMeta(
        Class<?> entityClass,
        EntityType<?> entityType,
        GqlEntityRegistration registration,
        String pkAttribute,
        Map<String, BasicAttr> basics,
        Map<String, ForwardRel> forwards,
        Map<String, ReverseRel> reverses) {

    public Collection<BasicAttr> basicAttributes() {
        return basics.values();
    }

    public Collection<ForwardRel> forwardRelations() {
        return forwards.values();
    }

    public Collection<ReverseRel> reverseRelations() {
        return reverses.values();
    }

    public BasicAttr basic(String name) {
        return basics.get(name);
    }

    public ForwardRel forward(String name) {
        return forwards.get(name);
    }

    public ReverseRel reverse(String name) {
        return reverses.get(name);
    }

    public boolean hasAttribute(String name) {
        return basics.containsKey(name) || forwards.containsKey(name) || reverses.containsKey(name);
    }

    public record BasicAttr(String name, Class<?> javaType) {

        public boolean isString() {
            return javaType == String.class;
        }

        public boolean isEnum() {
            return javaType.isEnum();
        }
    }

    public record ForwardRel(String name,
                             Class<?> targetClass,
                             String targetPkAttribute,
                             String targetIdAttribute) {
    }

    public record ReverseRel(String name,
                             Class<?> targetClass,
                             String mappedBy,
                             String ownIdAttribute) {
    }
}
