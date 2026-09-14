package com.bjarne.genericgraphql.engine.registry;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class GqlEntityRegistration {

    private final Class<?> entityClass;
    private final String endpointName;
    private final String typeName;
    private final String filterName;
    private final String orderByName;
    private final String inputName;
    private final ResolverType resolverType;
    private final String idAttribute;

    private final Set<String> fieldInclude;
    private final Set<String> fieldExclude;
    private final Set<String> filterInclude;
    private final Set<String> filterExclude;
    private final Set<String> orderByInclude;
    private final Set<String> orderByExclude;
    private final Set<String> inputInclude;
    private final Set<String> inputExclude;

    private GqlEntityRegistration(Builder builder) {
        this.entityClass = builder.entityClass;
        String simpleName = builder.entityClass.getSimpleName();
        this.endpointName = builder.endpointName != null
                ? builder.endpointName
                : Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
        this.typeName = builder.typeName != null ? builder.typeName : simpleName;
        this.filterName = builder.filterName != null ? builder.filterName : simpleName + "Filter";
        this.orderByName = builder.orderByName != null ? builder.orderByName : simpleName + "OrderBy";
        this.inputName = builder.inputName != null ? builder.inputName : simpleName + "Input";
        this.resolverType = builder.resolverType;
        this.idAttribute = builder.idAttribute;

        this.fieldInclude = freeze(builder.fieldInclude);
        this.fieldExclude = freeze(builder.fieldExclude);
        this.filterInclude = freeze(builder.filterInclude);
        this.filterExclude = freeze(builder.filterExclude);
        this.orderByInclude = freeze(builder.orderByInclude);
        this.orderByExclude = freeze(builder.orderByExclude);
        this.inputInclude = freeze(builder.inputInclude);
        this.inputExclude = freeze(builder.inputExclude);
    }

    private static Set<String> freeze(Set<String> source) {
        return source == null ? null : Collections.unmodifiableSet(new LinkedHashSet<>(source));
    }

    public static Builder of(Class<?> entityClass) {
        return new Builder(entityClass);
    }

    public boolean includes(String attributeName, TypeFieldType type) {
        Set<String> include = switch (type) {
            case TYPE -> fieldInclude;
            case FILTER -> filterInclude;
            case ORDER_BY -> orderByInclude;
            case INPUT -> inputInclude;
        };
        Set<String> exclude = switch (type) {
            case TYPE -> fieldExclude;
            case FILTER -> filterExclude;
            case ORDER_BY -> orderByExclude;
            case INPUT -> inputExclude;
        };
        if (exclude != null && exclude.contains(attributeName)) {
            return false;
        }
        return include == null || include.contains(attributeName);
    }

    public Class<?> entityClass() {
        return entityClass;
    }

    public String endpointName() {
        return endpointName;
    }

    public String typeName() {
        return typeName;
    }

    public String filterName() {
        return filterName;
    }

    public String orderByName() {
        return orderByName;
    }

    public String inputName() {
        return inputName;
    }

    public ResolverType resolverType() {
        return resolverType;
    }

    public String idAttribute() {
        return idAttribute;
    }

    public boolean eventSourced() {
        return resolverType == ResolverType.LATEST;
    }

    public static final class Builder {
        private final Class<?> entityClass;
        private String endpointName;
        private String typeName;
        private String filterName;
        private String orderByName;
        private String inputName;
        private ResolverType resolverType = ResolverType.LATEST;
        private String idAttribute = "objectBezugsId";

        private Set<String> fieldInclude;
        private final Set<String> fieldExclude = new LinkedHashSet<>();
        private Set<String> filterInclude;
        private final Set<String> filterExclude = new LinkedHashSet<>();
        private Set<String> orderByInclude;
        private final Set<String> orderByExclude = new LinkedHashSet<>();
        private Set<String> inputInclude;
        private final Set<String> inputExclude = new LinkedHashSet<>();

        private Builder(Class<?> entityClass) {
            this.entityClass = entityClass;
        }

        public Builder endpointName(String value) {
            this.endpointName = value;
            return this;
        }

        public Builder typeName(String value) {
            this.typeName = value;
            return this;
        }

        public Builder filterName(String value) {
            this.filterName = value;
            return this;
        }

        public Builder orderByName(String value) {
            this.orderByName = value;
            return this;
        }

        public Builder inputName(String value) {
            this.inputName = value;
            return this;
        }

        public Builder resolverType(ResolverType value) {
            this.resolverType = value;
            return this;
        }

        public Builder idAttribute(String value) {
            this.idAttribute = value;
            return this;
        }

        public Builder fieldInclude(String... names) {
            this.fieldInclude = Set.of(names);
            return this;
        }

        public Builder fieldExclude(String... names) {
            this.fieldExclude.addAll(Set.of(names));
            return this;
        }

        public Builder filterInclude(String... names) {
            this.filterInclude = Set.of(names);
            return this;
        }

        public Builder filterExclude(String... names) {
            this.filterExclude.addAll(Set.of(names));
            return this;
        }

        public Builder orderByInclude(String... names) {
            this.orderByInclude = Set.of(names);
            return this;
        }

        public Builder orderByExclude(String... names) {
            this.orderByExclude.addAll(Set.of(names));
            return this;
        }

        public Builder inputInclude(String... names) {
            this.inputInclude = Set.of(names);
            return this;
        }

        public Builder inputExclude(String... names) {
            this.inputExclude.addAll(Set.of(names));
            return this;
        }

        public GqlEntityRegistration build() {
            return new GqlEntityRegistration(this);
        }
    }
}
