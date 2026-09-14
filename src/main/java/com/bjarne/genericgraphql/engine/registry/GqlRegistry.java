package com.bjarne.genericgraphql.engine.registry;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class GqlRegistry {

    private final Map<Class<?>, GqlEntityRegistration> registrations = new LinkedHashMap<>();

    public GqlRegistry register(GqlEntityRegistration registration) {
        registrations.put(registration.entityClass(), registration);
        return this;
    }

    public boolean isRegistered(Class<?> entityClass) {
        return registrations.containsKey(entityClass);
    }

    public GqlEntityRegistration get(Class<?> entityClass) {
        GqlEntityRegistration registration = registrations.get(entityClass);
        if (registration == null) {
            throw new IllegalStateException("Entity nicht am Schema registriert: " + entityClass.getName());
        }
        return registration;
    }

    public GqlEntityRegistration find(Class<?> entityClass) {
        return registrations.get(entityClass);
    }

    public Collection<GqlEntityRegistration> all() {
        return registrations.values();
    }

    public Set<Class<?>> entityClasses() {
        return registrations.keySet();
    }
}
