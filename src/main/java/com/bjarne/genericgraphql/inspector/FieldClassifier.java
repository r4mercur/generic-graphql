package com.bjarne.genericgraphql.inspector;

import com.bjarne.genericgraphql.engine.dataloader.RelationDataLoaders;
import com.bjarne.genericgraphql.engine.meta.EntityMeta;
import com.bjarne.genericgraphql.engine.meta.MetaProvider;
import com.bjarne.genericgraphql.engine.registry.GqlEntityRegistration;
import com.bjarne.genericgraphql.engine.registry.GqlRegistry;
import com.bjarne.genericgraphql.engine.remote.RemoteField;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Ordnet jedem Feld des generierten Schemas zu, wer es aufloest: Query-Resolver, DataLoader
 * (forward/reverse), Remote-Loader oder die Mutation. Abgeleitet aus derselben Registry, aus der
 * {@code SchemaFactory} das Schema baut - die Zuordnung kann also nicht auseinanderlaufen.
 */
@Component
@ConditionalOnProperty(name = "inspector.enabled", havingValue = "true", matchIfMissing = true)
public class FieldClassifier {

    /** Was hinter einem Feld steckt. {@code source} ist die Spur in der Timeline (db, trust, ...). */
    public record Kind(String kind,
                       String via,
                       String resolver,
                       String loaderKey,
                       String source,
                       String sourceName,
                       GqlEntityRegistration registration,
                       RemoteField<?> remote) {
    }

    private final MetaProvider metaProvider;
    private final GqlRegistry registry;
    private final String dbName;
    private volatile Map<String, Kind> kinds;
    private volatile Map<String, RemoteField<?>> remotes;

    public FieldClassifier(MetaProvider metaProvider, GqlRegistry registry, Environment environment) {
        this.metaProvider = metaProvider;
        this.registry = registry;
        this.dbName = databaseName(environment.getProperty("spring.datasource.url", "")) + " \u00b7 JPA";
    }

    public Kind classify(String typeName, String fieldName) {
        Kind kind = kinds().get(typeName + "." + fieldName);
        return kind != null ? kind : new Kind("field", "", "", null, "db", dbName, null, null);
    }

    /** Das Remote-Feld, dessen Owner eine Filter-Suche beantwortet. */
    public RemoteField<?> remote(String source, String fieldName) {
        kinds();
        return remotes.get(source + "#" + fieldName);
    }

    public String dbName() {
        return dbName;
    }

    /** Kurzform eines Service-Namens als Spur: "Trust-Service" wird zu "trust". */
    public static String lane(String source) {
        if (source == null || source.isBlank()) {
            return "remote";
        }
        String lane = source.toLowerCase(Locale.ROOT)
                .replaceAll("[-_ ]?service$", "")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return lane.isEmpty() || lane.equals("thread") || lane.equals("db") || lane.equals("root") ? "remote" : lane;
    }

    private Map<String, Kind> kinds() {
        Map<String, Kind> map = kinds;
        if (map == null) {
            synchronized (this) {
                if (kinds == null) {
                    build();
                }
                map = kinds;
            }
        }
        return map;
    }

    private void build() {
        Map<String, Kind> map = new HashMap<>();
        Map<String, RemoteField<?>> remoteMap = new HashMap<>();
        for (EntityMeta meta : metaProvider.allMetas()) {
            GqlEntityRegistration reg = meta.registration();
            String endpoint = reg.endpointName();
            String type = reg.typeName();

            String listField = reg.eventSourced() ? endpoint + "LatestVersions" : endpoint + "List";
            map.put("Query." + listField, db("list", "list()", "GenericDataFetchers.list", null, reg));
            map.put("Query." + endpoint + "ById", db("byId", "byId()", "GenericDataFetchers.byObjectId", null, reg));
            if (reg.eventSourced()) {
                map.put("Query." + endpoint + "Timestamps", db("timestamps", "timestamps()", "GenericDataFetchers.timestamps", null, reg));
                map.put("Query." + endpoint + "Log", db("log", "log()", "GenericDataFetchers.log", null, reg));
                map.put("Query." + endpoint + "Relations", db("relations", "relations()", "GenericDataFetchers.relationsFinder", null, reg));
                map.put("Mutation." + endpoint + "Event", db("mutation", "mutation", "GenericDataFetchers.eventMutation", null, reg));
            }

            for (EntityMeta.ForwardRel rel : meta.forwardRelations()) {
                map.put(type + "." + rel.name(), db("forward", "forward", "GenericDataFetchers.forward",
                        RelationDataLoaders.forwardKey(rel.targetClass()), registry.find(rel.targetClass())));
            }
            for (EntityMeta.ReverseRel rel : meta.reverseRelations()) {
                map.put(type + "." + rel.name(), db("reverse", "reverse", "GenericDataFetchers.reverse",
                        RelationDataLoaders.reverseKey(rel.targetClass(), rel.mappedBy()), registry.find(rel.targetClass())));
            }
            for (RemoteField<?> remote : reg.remoteFields()) {
                map.put(type + "." + remote.name(), new Kind("remote", "remote", "GenericDataFetchers.remote",
                        remote.loaderKey(type), lane(remote.source()), remote.source(), reg, remote));
                remoteMap.put(remote.source() + "#" + remote.name(), remote);
            }
        }
        remotes = Map.copyOf(remoteMap);
        kinds = Map.copyOf(map);
    }

    private Kind db(String kind, String via, String resolver, String loaderKey, GqlEntityRegistration registration) {
        return new Kind(kind, via, resolver, loaderKey, "db", dbName, registration, null);
    }

    private static String databaseName(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("jdbc:h2:")) {
            return "H2";
        }
        if (lower.startsWith("jdbc:postgresql:")) {
            return "PostgreSQL";
        }
        return "DB";
    }
}
