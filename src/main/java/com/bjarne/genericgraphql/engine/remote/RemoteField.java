package com.bjarne.genericgraphql.engine.remote;

import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Ein Feld, dessen Wert nicht in der eigenen Datenbank liegt, sondern von einem anderen
 * Service geliefert wird. Der Werttyp ist ein Record; aus seinen Komponenten entsteht der
 * GraphQL-Objekttyp.
 *
 * <p>Ohne weiteres ist ein Remote-Feld nur selektierbar. Gibt der Owner Felder zum Filtern
 * frei ({@link Builder#filterable}), werden Filter darauf an seine Suche delegiert - Filter
 * sind auch Lesen, deshalb entscheidet er, welche. Sortieren nach Remote-Feldern geht nicht.
 */
public record RemoteField<V>(String name,
                             Class<V> valueType,
                             String typeName,
                             String keyAttribute,
                             String source,
                             Duration timeout,
                             RemoteBatchLoader<V> loader,
                             Set<String> filterFields,
                             RemoteSearch search,
                             int maxIds) {

    public static <V> Builder<V> of(String name, Class<V> valueType) {
        return new Builder<>(name, valueType);
    }

    public String loaderKey(String ownerTypeName) {
        return "remote:" + ownerTypeName + "#" + name;
    }

    public boolean searchable() {
        return search != null && !filterFields.isEmpty();
    }

    public String filterTypeName() {
        return typeName + "Filter";
    }

    public Class<?> componentType(String component) {
        return Arrays.stream(valueType.getRecordComponents())
                .filter(c -> c.getName().equals(component))
                .map(RecordComponent::getType)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        valueType.getSimpleName() + " hat kein Feld " + component));
    }

    public static final class Builder<V> {
        private final String name;
        private final Class<V> valueType;
        private String typeName;
        private String keyAttribute = "objectRefId";
        private String source = "Remote-Service";
        private Duration timeout = Duration.ofSeconds(2);
        private RemoteBatchLoader<V> loader;
        private Set<String> filterFields = Set.of();
        private RemoteSearch search;
        private int maxIds = 1_000;

        private Builder(String name, Class<V> valueType) {
            this.name = name;
            this.valueType = valueType;
        }

        public Builder<V> typeName(String value) {
            this.typeName = value;
            return this;
        }

        /** Attribut der eigenen Entity, das als Schluessel an den fremden Service geht. */
        public Builder<V> keyAttribute(String value) {
            this.keyAttribute = value;
            return this;
        }

        /** Name des Service - erscheint in Schema-Beschreibung und Fehlermeldungen. */
        public Builder<V> source(String value) {
            this.source = value;
            return this;
        }

        public Builder<V> timeout(Duration value) {
            this.timeout = value;
            return this;
        }

        public Builder<V> loader(RemoteBatchLoader<V> value) {
            this.loader = value;
            return this;
        }

        /** Der Owner gibt diese Felder zum Filtern frei und beantwortet Filter darauf selbst. */
        public Builder<V> filterable(RemoteSearch value, String... fields) {
            this.search = value;
            this.filterFields = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(fields)));
            return this;
        }

        /** Mehr Treffer beim Owner brechen die Query ab, statt ein IN beliebiger Groesse zu bauen. */
        public Builder<V> maxIds(int value) {
            this.maxIds = value;
            return this;
        }

        public RemoteField<V> build() {
            if (!valueType.isRecord()) {
                throw new IllegalArgumentException("Werttyp eines Remote-Felds muss ein Record sein: " + valueType);
            }
            Objects.requireNonNull(loader, "Remote-Feld " + name + " braucht einen Loader");
            RemoteField<V> field = new RemoteField<>(name, valueType,
                    typeName != null ? typeName : valueType.getSimpleName(),
                    keyAttribute, source, timeout, loader, filterFields, search, maxIds);
            filterFields.forEach(field::componentType);
            return field;
        }
    }
}
