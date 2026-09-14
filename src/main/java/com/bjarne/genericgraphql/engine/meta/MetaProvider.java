package com.bjarne.genericgraphql.engine.meta;

import com.bjarne.genericgraphql.engine.registry.GqlEntityRegistration;
import com.bjarne.genericgraphql.engine.registry.GqlRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.PluralAttribute;
import jakarta.persistence.metamodel.SingularAttribute;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class MetaProvider {

    private final EntityManager entityManager;
    private final GqlRegistry registry;
    private final Map<Class<?>, EntityMeta> cache = new ConcurrentHashMap<>();

    public MetaProvider(EntityManager entityManager, GqlRegistry registry) {
        this.entityManager = entityManager;
        this.registry = registry;
    }

    public EntityMeta meta(Class<?> entityClass) {
        return cache.computeIfAbsent(entityClass, this::build);
    }

    public EntityMeta meta(GqlEntityRegistration registration) {
        return meta(registration.entityClass());
    }

    public List<EntityMeta> allMetas() {
        return registry.all().stream().map(this::meta).toList();
    }

    private EntityMeta build(Class<?> entityClass) {
        GqlEntityRegistration registration = registry.get(entityClass);
        EntityType<?> entityType = entityManager.getMetamodel().entity(entityClass);

        Map<String, EntityMeta.BasicAttr> basics = new LinkedHashMap<>();
        Map<String, EntityMeta.ForwardRel> forwards = new LinkedHashMap<>();
        Map<String, EntityMeta.ReverseRel> reverses = new LinkedHashMap<>();

        List<Attribute<?, ?>> attributes = new ArrayList<>(entityType.getAttributes());
        attributes.sort(Comparator.comparing(Attribute::getName));

        for (Attribute<?, ?> attribute : attributes) {
            String name = attribute.getName();
            switch (attribute.getPersistentAttributeType()) {
                case BASIC -> basics.put(name, new EntityMeta.BasicAttr(name, boxed(attribute.getJavaType())));
                case MANY_TO_ONE, ONE_TO_ONE -> {
                    Class<?> target = attribute.getJavaType();
                    GqlEntityRegistration targetReg = registry.find(target);
                    if (targetReg != null) {
                        forwards.put(name, new EntityMeta.ForwardRel(
                                name,
                                target,
                                pkAttribute(target),
                                targetReg.idAttribute()));
                    }
                }
                case ONE_TO_MANY, MANY_TO_MANY -> {
                    Class<?> target = ((PluralAttribute<?, ?, ?>) attribute).getElementType().getJavaType();
                    GqlEntityRegistration targetReg = registry.find(target);
                    String mappedBy = mappedBy(attribute);
                    if (targetReg != null && mappedBy != null && !mappedBy.isBlank()) {
                        reverses.put(name, new EntityMeta.ReverseRel(
                                name,
                                target,
                                mappedBy,
                                registration.idAttribute()));
                    }
                }
                default -> {
                    // eingebettete Typen und Element-Collections bleiben aussen vor
                }
            }
        }

        return new EntityMeta(
                entityClass,
                entityType,
                registration,
                pkAttribute(entityClass),
                Map.copyOf(basics),
                Map.copyOf(forwards),
                Map.copyOf(reverses));
    }

    private String pkAttribute(Class<?> entityClass) {
        EntityType<?> type = entityManager.getMetamodel().entity(entityClass);
        SingularAttribute<?, ?> id = type.getId(type.getIdType().getJavaType());
        return id.getName();
    }

    private static String mappedBy(Attribute<?, ?> attribute) {
        Member member = attribute.getJavaMember();
        OneToMany oneToMany = annotation(member, OneToMany.class);
        if (oneToMany != null) {
            return oneToMany.mappedBy();
        }
        ManyToMany manyToMany = annotation(member, ManyToMany.class);
        return manyToMany != null ? manyToMany.mappedBy() : null;
    }

    private static <A extends java.lang.annotation.Annotation> A annotation(Member member, Class<A> type) {
        if (member instanceof Field field) {
            return field.getAnnotation(type);
        }
        if (member instanceof Method method) {
            return method.getAnnotation(type);
        }
        return null;
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        return switch (type.getName()) {
            case "boolean" -> Boolean.class;
            case "byte" -> Byte.class;
            case "short" -> Short.class;
            case "int" -> Integer.class;
            case "long" -> Long.class;
            case "float" -> Float.class;
            case "double" -> Double.class;
            case "char" -> Character.class;
            default -> type;
        };
    }
}
