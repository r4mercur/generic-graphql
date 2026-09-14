package com.bjarne.genericgraphql.engine.query;

import com.bjarne.genericgraphql.domain.EventType;
import com.bjarne.genericgraphql.engine.filter.FilterEngine;
import com.bjarne.genericgraphql.engine.meta.EntityMeta;
import com.bjarne.genericgraphql.engine.order.OrderByEngine;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class EntityQueryService {

    private final EntityManager entityManager;
    private final FilterEngine filterEngine;
    private final OrderByEngine orderByEngine;

    public EntityQueryService(EntityManager entityManager,
                              FilterEngine filterEngine,
                              OrderByEngine orderByEngine) {
        this.entityManager = entityManager;
        this.filterEngine = filterEngine;
        this.orderByEngine = orderByEngine;
    }

    /** Zusaetzliche Einschraenkung, die ein Aufrufer beisteuern kann (z.B. DataLoader-Keys). */
    @FunctionalInterface
    public interface ExtraPredicate {
        Predicate apply(CriteriaBuilder cb, Root<Object> root, CriteriaQuery<?> query);
    }

    public record QuerySpec(EntityMeta meta,
                            Map<String, Object> filter,
                            Map<String, Object> orderBy,
                            Boolean active,
                            Integer limit,
                            Integer offset,
                            FilterEngine.FilterContext context,
                            ExtraPredicate extra) {
    }

    @SuppressWarnings("unchecked")
    public List<Object> find(QuerySpec spec) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Object> query = (CriteriaQuery<Object>) cb.createQuery(spec.meta().entityClass());
        Root<Object> root = (Root<Object>) query.from(spec.meta().entityClass());

        List<Predicate> predicates = new ArrayList<>();
        predicates.add(filterEngine.scope(cb, query, root, spec.meta(), spec.context()));

        if (spec.active() != null && spec.meta().basic("aktiv") != null) {
            predicates.add(cb.equal(root.get("aktiv"), spec.active()));
        }
        if (spec.filter() != null && !spec.filter().isEmpty()) {
            predicates.add(filterEngine.buildRoot(cb, query, root, spec.meta(), spec.filter(), spec.context()));
        }
        if (spec.extra() != null) {
            predicates.add(spec.extra().apply(cb, root, query));
        }

        query.select(root).where(cb.and(predicates.toArray(new Predicate[0])));

        List<Order> orders = spec.orderBy() == null
                ? List.of()
                : orderByEngine.build(cb, root, spec.meta(), spec.orderBy());
        if (!orders.isEmpty()) {
            query.orderBy(orders);
        } else {
            query.orderBy(cb.asc(root.get(spec.meta().registration().idAttribute())));
        }

        TypedQuery<Object> typedQuery = entityManager.createQuery(query);
        if (spec.offset() != null && spec.offset() > 0) {
            typedQuery.setFirstResult(spec.offset());
        }
        if (spec.limit() != null) {
            typedQuery.setMaxResults(spec.limit());
        }
        return typedQuery.getResultList();
    }

    /**
     * Alle Gueltigkeitszeitpunkte eines Objekts - die Basis fuer
     * "wie sah das Objekt vor Aenderung X aus?".
     */
    public List<LocalDate> timestamps(EntityMeta meta, String objectBezugsId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<LocalDate> query = cb.createQuery(LocalDate.class);
        Root<?> root = query.from(meta.entityClass());

        query.select(root.<LocalDate>get("gueltigVon")).distinct(true);
        query.where(cb.and(
                cb.equal(root.get("objectBezugsId"), objectBezugsId),
                cb.equal(root.get("event"), EventType.SNAPSHOT_VALUE)));
        query.orderBy(cb.asc(root.get("gueltigVon")));

        return entityManager.createQuery(query).getResultList();
    }

    /** Die reine Event-Historie (ohne Snapshots) eines Objekts. */
    @SuppressWarnings("unchecked")
    public List<Object> eventLog(EntityMeta meta, String objectBezugsId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Object> query = (CriteriaQuery<Object>) cb.createQuery(meta.entityClass());
        Root<Object> root = (Root<Object>) query.from(meta.entityClass());

        query.select(root).where(cb.and(
                cb.equal(root.get("objectBezugsId"), objectBezugsId),
                cb.notEqual(root.get("event"), EventType.SNAPSHOT_VALUE)));
        query.orderBy(cb.asc(root.get("gueltigVon")), cb.asc(root.get("objectId")));

        return entityManager.createQuery(query).getResultList();
    }

    public EntityManager entityManager() {
        return entityManager;
    }
}
