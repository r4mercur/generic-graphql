package com.bjarne.genericgraphql.engine.remote;

import java.time.Duration;
import java.util.Objects;

/**
 * Ein Feld, dessen Wert nicht in der eigenen Datenbank liegt, sondern von einem anderen
 * Service geliefert wird. Der Werttyp ist ein Record; aus seinen Komponenten entsteht der
 * GraphQL-Objekttyp.
 *
 * <p>Remote-Felder sind nur selektierbar. Filter, OrderBy und WILDCARDFILTER werden in SQL
 * uebersetzt und koennen den fremden Service nicht erreichen - deshalb tauchen sie dort gar
 * nicht erst auf.
 */
public record RemoteField<V>(String name,
                             Class<V> valueType,
                             String typeName,
                             String keyAttribute,
                             String source,
                             Duration timeout,
                             RemoteBatchLoader<V> loader) {

    public static <V> Builder<V> of(String name, Class<V> valueType) {
        return new Builder<>(name, valueType);
    }

    public String loaderKey(String ownerTypeName) {
        return "remote:" + ownerTypeName + "#" + name;
    }

    public static final class Builder<V> {
        private final String name;
        private final Class<V> valueType;
        private String typeName;
        private String keyAttribute = "objectRefId";
        private String source = "Remote-Service";
        private Duration timeout = Duration.ofSeconds(2);
        private RemoteBatchLoader<V> loader;

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

        public RemoteField<V> build() {
            if (!valueType.isRecord()) {
                throw new IllegalArgumentException("Werttyp eines Remote-Felds muss ein Record sein: " + valueType);
            }
            Objects.requireNonNull(loader, "Remote-Feld " + name + " braucht einen Loader");
            return new RemoteField<>(name, valueType,
                    typeName != null ? typeName : valueType.getSimpleName(),
                    keyAttribute, source, timeout, loader);
        }
    }
}
