package com.bjarne.genericgraphql.engine.dataloader;

import com.bjarne.genericgraphql.engine.filter.FilterEngine;
import com.bjarne.genericgraphql.engine.meta.EntityMeta;
import com.bjarne.genericgraphql.engine.meta.MetaProvider;
import com.bjarne.genericgraphql.engine.query.EntityQueryService;
import com.bjarne.genericgraphql.engine.query.RequestState;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.dataloader.DataLoader;
import org.dataloader.DataLoaderFactory;
import org.dataloader.DataLoaderRegistry;
import org.springframework.stereotype.Component;

@Component
public class RelationDataLoaders {

    public static final String CONTEXT_KEY = "gql.dataloaders";

    private final MetaProvider metaProvider;
    private final EntityQueryService queryService;

    public RelationDataLoaders(MetaProvider metaProvider, EntityQueryService queryService) {
        this.metaProvider = metaProvider;
        this.queryService = queryService;
    }

    public static String forwardKey(Class<?> targetClass) {
        return "forward:" + targetClass.getName();
    }

    public static String reverseKey(Class<?> targetClass, String mappedBy) {
        return "reverse:" + targetClass.getName() + "#" + mappedBy;
    }

    public DataLoaderRegistry newRegistry(RequestState state) {
        DataLoaderRegistry registry = new DataLoaderRegistry();
        Set<String> registered = new HashSet<>();

        for (EntityMeta meta : metaProvider.allMetas()) {
            for (EntityMeta.ForwardRel rel : meta.forwardRelations()) {
                String key = forwardKey(rel.targetClass());
                if (registered.add(key)) {
                    registry.register(key, forwardLoader(rel, state));
                }
            }
            for (EntityMeta.ReverseRel rel : meta.reverseRelations()) {
                String key = reverseKey(rel.targetClass(), rel.mappedBy());
                if (registered.add(key)) {
                    registry.register(key, reverseLoader(rel, state));
                }
            }
        }
        return registry;
    }

    private DataLoader<Object, Object> forwardLoader(EntityMeta.ForwardRel rel, RequestState state) {
        EntityMeta targetMeta = metaProvider.meta(rel.targetClass());
        String idAttribute = rel.targetIdAttribute();

        return DataLoaderFactory.newDataLoader(keys -> CompletableFuture.completedFuture(
                loadForward(targetMeta, idAttribute, keys, contextOf(state))));
    }

    private List<Object> loadForward(EntityMeta targetMeta,
                                     String idAttribute,
                                     List<Object> keys,
                                     FilterEngine.FilterContext context) {

        List<Object> rows = queryService.find(new EntityQueryService.QuerySpec(
                targetMeta, null, null, null, null, null, context,
                (cb, root, query) -> root.get(idAttribute).in(keys)));

        Map<Object, Object> byKey = new LinkedHashMap<>();
        for (Object row : rows) {
            byKey.put(readAttribute(row, idAttribute), row);
        }
        return keys.stream().map(byKey::get).toList();
    }

    private DataLoader<Object, List<Object>> reverseLoader(EntityMeta.ReverseRel rel, RequestState state) {
        EntityMeta targetMeta = metaProvider.meta(rel.targetClass());
        EntityMeta.ForwardRel backRef = targetMeta.forward(rel.mappedBy());

        return DataLoaderFactory.newDataLoader(keys -> CompletableFuture.completedFuture(
                loadReverse(targetMeta, rel, backRef, keys, contextOf(state))));
    }

    private List<List<Object>> loadReverse(EntityMeta targetMeta,
                                           EntityMeta.ReverseRel rel,
                                           EntityMeta.ForwardRel backRef,
                                           List<Object> keys,
                                           FilterEngine.FilterContext context) {

        List<Object> rows = queryService.find(new EntityQueryService.QuerySpec(
                targetMeta, null, null, null, null, null, context,
                (cb, root, query) -> root.get(rel.mappedBy()).get(backRef.targetPkAttribute()).in(keys)));

        Map<Object, List<Object>> byKey = new LinkedHashMap<>();
        for (Object row : rows) {
            Object parent = readAttribute(row, rel.mappedBy());
            Object key = parent == null ? null : readAttribute(parent, backRef.targetPkAttribute());
            if (key != null) {
                byKey.computeIfAbsent(key, unused -> new ArrayList<>()).add(row);
            }
        }
        return keys.stream()
                .map(key -> byKey.getOrDefault(key, List.<Object>of()))
                .map(list -> (List<Object>) new ArrayList<>(list))
                .toList();
    }

    private FilterEngine.FilterContext contextOf(RequestState state) {
        return new FilterEngine.FilterContext(state.date(), null, state.graphQLContext());
    }

    public static Object readAttribute(Object entity, String attribute) {
        if (entity == null) {
            return null;
        }
        String getter = "get" + Character.toUpperCase(attribute.charAt(0)) + attribute.substring(1);
        try {
            return entity.getClass().getMethod(getter).invoke(entity);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(
                    "Kann Attribut " + attribute + " von " + entity.getClass().getSimpleName() + " nicht lesen", ex);
        }
    }
}
