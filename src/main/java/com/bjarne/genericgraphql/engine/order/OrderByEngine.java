package com.bjarne.genericgraphql.engine.order;

import com.bjarne.genericgraphql.engine.meta.EntityMeta;
import com.bjarne.genericgraphql.engine.meta.MetaProvider;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Order;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class OrderByEngine {

    private final MetaProvider metaProvider;

    public OrderByEngine(MetaProvider metaProvider) {
        this.metaProvider = metaProvider;
    }

    public List<Order> build(CriteriaBuilder cb, From<?, ?> root, EntityMeta meta, Map<String, Object> orderBy) {
        List<Order> orders = new ArrayList<>();
        collect(cb, root, meta, orderBy, orders);
        return orders;
    }

    private void collect(CriteriaBuilder cb,
                         From<?, ?> path,
                         EntityMeta meta,
                         Map<String, Object> orderBy,
                         List<Order> out) {

        if (orderBy == null) {
            return;
        }

        for (Map.Entry<String, Object> entry : orderBy.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            String name = entry.getKey();

            if (value instanceof Map<?, ?> nested) {
                EntityMeta.ForwardRel rel = meta.forward(name);
                if (rel == null) {
                    continue;
                }
                From<?, ?> join = path.join(name, JoinType.LEFT);
                @SuppressWarnings("unchecked")
                Map<String, Object> nestedMap = (Map<String, Object>) nested;
                collect(cb, join, metaProvider.meta(rel.targetClass()), nestedMap, out);
                continue;
            }

            String direction = String.valueOf(value);
            if ("DESC".equalsIgnoreCase(direction)) {
                out.add(cb.desc(path.get(name)));
            } else {
                out.add(cb.asc(path.get(name)));
            }
        }
    }
}
