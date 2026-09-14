package com.bjarne.genericgraphql.engine.relations;

import com.bjarne.genericgraphql.engine.dataloader.RelationDataLoaders;
import com.bjarne.genericgraphql.engine.filter.FilterEngine;
import com.bjarne.genericgraphql.engine.meta.EntityMeta;
import com.bjarne.genericgraphql.engine.meta.MetaProvider;
import com.bjarne.genericgraphql.engine.query.EntityQueryService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class RelationsFinderService {

    private final MetaProvider metaProvider;
    private final EntityQueryService queryService;

    public RelationsFinderService(MetaProvider metaProvider, EntityQueryService queryService) {
        this.metaProvider = metaProvider;
        this.queryService = queryService;
    }

    public record RelationRef(String relation, String endpoint, String id) {
    }

    public record Relations(List<RelationRef> forwardRelations, List<RelationRef> reverseRelations) {

        public static Relations empty() {
            return new Relations(List.of(), List.of());
        }
    }

    public Relations find(EntityMeta meta, Object entity, FilterEngine.FilterContext context) {
        if (entity == null) {
            return Relations.empty();
        }

        List<RelationRef> forwards = new ArrayList<>();
        for (EntityMeta.ForwardRel rel : meta.forwardRelations()) {
            Object target = RelationDataLoaders.readAttribute(entity, rel.name());
            if (target == null) {
                continue;
            }
            Object id = RelationDataLoaders.readAttribute(target, rel.targetPkAttribute());
            if (id == null) {
                continue;
            }
            EntityMeta targetMeta = metaProvider.meta(rel.targetClass());
            forwards.add(new RelationRef(
                    targetMeta.registration().typeName(),
                    targetMeta.registration().endpointName(),
                    String.valueOf(id)));
        }

        List<RelationRef> reverses = new ArrayList<>(findReverse(meta, entity, context).values());
        return new Relations(forwards, reverses);
    }

    public Map<String, RelationRef> findReverse(EntityMeta meta,
                                                Object entity,
                                                FilterEngine.FilterContext context) {

        Object ownId = RelationDataLoaders.readAttribute(entity, meta.registration().idAttribute());
        Map<String, RelationRef> found = new LinkedHashMap<>();
        if (ownId == null) {
            return found;
        }

        for (EntityMeta.ReverseRel rel : meta.reverseRelations()) {
            EntityMeta targetMeta = metaProvider.meta(rel.targetClass());
            EntityMeta.ForwardRel backRef = targetMeta.forward(rel.mappedBy());
            if (backRef == null) {
                continue;
            }

            List<Object> rows = queryService.find(new EntityQueryService.QuerySpec(
                    targetMeta, null, null, null, null, null, context,
                    (cb, root, query) -> cb.equal(
                            root.get(rel.mappedBy()).get(backRef.targetPkAttribute()), ownId)));

            for (Object row : rows) {
                Object id = RelationDataLoaders.readAttribute(row, targetMeta.registration().idAttribute());
                if (id == null) {
                    continue;
                }
                found.put(targetMeta.registration().typeName() + "/" + id, new RelationRef(
                        targetMeta.registration().typeName(),
                        targetMeta.registration().endpointName(),
                        String.valueOf(id)));
            }
        }
        return found;
    }
}
