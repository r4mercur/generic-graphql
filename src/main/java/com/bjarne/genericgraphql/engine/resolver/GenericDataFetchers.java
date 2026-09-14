package com.bjarne.genericgraphql.engine.resolver;

import com.bjarne.genericgraphql.engine.dataloader.RelationDataLoaders;
import com.bjarne.genericgraphql.engine.filter.FilterEngine;
import com.bjarne.genericgraphql.engine.meta.EntityMeta;
import com.bjarne.genericgraphql.engine.query.RequestState;
import com.bjarne.genericgraphql.engine.mutation.EventMutationService;
import com.bjarne.genericgraphql.engine.query.EntityQueryService;
import com.bjarne.genericgraphql.engine.registry.TypeFieldType;
import com.bjarne.genericgraphql.engine.relations.RelationsFinderService;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.dataloader.DataLoader;
import org.dataloader.DataLoaderRegistry;
import org.springframework.stereotype.Component;

@Component
public class GenericDataFetchers {

    public static final int DEFAULT_LIMIT = 100;

    private final EntityQueryService queryService;
    private final RelationsFinderService relationsFinder;
    private final EventMutationService mutationService;

    public GenericDataFetchers(EntityQueryService queryService,
                               RelationsFinderService relationsFinder,
                               EventMutationService mutationService) {
        this.queryService = queryService;
        this.relationsFinder = relationsFinder;
        this.mutationService = mutationService;
    }

    // -------------------------------------------------------------------------
    // Query-Resolver
    // -------------------------------------------------------------------------

    public DataFetcher<List<Object>> list(EntityMeta meta) {
        return env -> queryService.find(new EntityQueryService.QuerySpec(
                meta,
                argMap(env, "filter"),
                argMap(env, "order"),
                env.getArgument("active"),
                intArg(env, "limit", DEFAULT_LIMIT),
                intArg(env, "offset", 0),
                context(env),
                null));
    }

    public DataFetcher<Object> byObjectId(EntityMeta meta) {
        String idAttribute = meta.registration().idAttribute();
        return env -> {
            Object id = env.getArgument(idAttribute);
            if (id == null) {
                throw new IllegalArgumentException(idAttribute + " fehlt");
            }
            List<Object> rows = queryService.find(new EntityQueryService.QuerySpec(
                    meta, null, null, env.getArgument("active"), 2, null, context(env),
                    (cb, root, query) -> cb.equal(root.get(idAttribute), id)));
            if (rows.size() > 1) {
                throw new IllegalStateException("Mehr als ein Objekt zu " + idAttribute + " = " + id);
            }
            return rows.isEmpty() ? null : rows.getFirst();
        };
    }

    public DataFetcher<List<LocalDate>> timestamps(EntityMeta meta) {
        return env -> queryService.timestamps(meta, env.getArgument("objectBezugsId"));
    }

    public DataFetcher<List<Map<String, Object>>> log(EntityMeta meta) {
        return env -> {
            List<Object> rows = queryService.eventLog(meta, env.getArgument("objectBezugsId"));
            return rows.stream().map(row -> toMap(meta, row)).toList();
        };
    }

    public DataFetcher<RelationsFinderService.Relations> relationsFinder(EntityMeta meta) {
        return env -> {
            Object entity = byObjectId(meta).get(env);
            return entity == null
                    ? RelationsFinderService.Relations.empty()
                    : relationsFinder.find(meta, entity, context(env));
        };
    }

    // -------------------------------------------------------------------------
    // Relations-Resolver (DataLoader)
    // -------------------------------------------------------------------------

    public DataFetcher<CompletableFuture<Object>> forward(EntityMeta.ForwardRel rel) {
        String loaderKey = RelationDataLoaders.forwardKey(rel.targetClass());
        return env -> {
            Object target = RelationDataLoaders.readAttribute(env.getSource(), rel.name());
            if (target == null) {
                return CompletableFuture.completedFuture(null);
            }
            // Nur die ID des Lazy-Proxys lesen - das loest ihn nicht auf.
            Object key = RelationDataLoaders.readAttribute(target, rel.targetPkAttribute());
            if (key == null) {
                return CompletableFuture.completedFuture(null);
            }
            return this.<Object, Object>loader(env, loaderKey).load(key);
        };
    }

    public DataFetcher<CompletableFuture<List<Object>>> reverse(EntityMeta.ReverseRel rel) {
        String loaderKey = RelationDataLoaders.reverseKey(rel.targetClass(), rel.mappedBy());
        return env -> {
            Object key = RelationDataLoaders.readAttribute(env.getSource(), rel.ownIdAttribute());
            if (key == null) {
                return CompletableFuture.completedFuture(List.of());
            }
            return this.<Object, List<Object>>loader(env, loaderKey).load(key);
        };
    }

    @SuppressWarnings("unchecked")
    private <K, V> DataLoader<K, V> loader(DataFetchingEnvironment env, String key) {
        DataLoaderRegistry registry = env.getGraphQlContext().get(RelationDataLoaders.CONTEXT_KEY);
        DataLoader<?, ?> loader = registry.getDataLoader(key);
        if (loader == null) {
            throw new IllegalStateException("Kein DataLoader registriert fuer " + key);
        }
        return (DataLoader<K, V>) loader;
    }

    // -------------------------------------------------------------------------
    // Mutation
    // -------------------------------------------------------------------------

    public DataFetcher<EventMutationService.Result> eventMutation(EntityMeta meta) {
        return env -> {
            Map<String, Object> data = argMap(env, "data");
            if (data == null) {
                return EventMutationService.Result.error("Input 'data' fehlt");
            }
            return mutationService.handle(meta, data, context(env));
        };
    }

    // -------------------------------------------------------------------------

    private FilterEngine.FilterContext context(DataFetchingEnvironment env) {
        RequestState state = env.getGraphQlContext().get(RequestState.CONTEXT_KEY);
        state.graphQLContext(env.getGraphQlContext());

        // Jedes Top-Level-Feld deklariert sein eigenes date-Argument; fehlt es,
        // ist "heute" gemeint - nicht der Stichtag eines Nachbarfelds.
        LocalDate own = env.getArgument("date");
        state.dateIfAbsent(own);

        LocalDate effective = own != null ? own : LocalDate.now();
        return new FilterEngine.FilterContext(effective, env.getSelectionSet(), env.getGraphQlContext());
    }

    private Map<String, Object> toMap(EntityMeta meta, Object row) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (EntityMeta.BasicAttr attr : meta.basicAttributes()) {
            if (!meta.registration().includes(attr.name(), TypeFieldType.TYPE)) {
                continue;
            }
            map.put(attr.name(), jsonSafe(RelationDataLoaders.readAttribute(row, attr.name())));
        }
        for (EntityMeta.ForwardRel rel : meta.forwardRelations()) {
            Object target = RelationDataLoaders.readAttribute(row, rel.name());
            Object id = target == null ? null : RelationDataLoaders.readAttribute(target, rel.targetPkAttribute());
            map.put(rel.name(), id == null ? null : String.valueOf(id));
        }
        return map;
    }

    private static Object jsonSafe(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> argMap(DataFetchingEnvironment env, String name) {
        Object value = env.getArgument(name);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static Integer intArg(DataFetchingEnvironment env, String name, Integer fallback) {
        Object value = env.getArgument(name);
        if (value == null) {
            return fallback;
        }
        return ((Number) value).intValue();
    }

}
