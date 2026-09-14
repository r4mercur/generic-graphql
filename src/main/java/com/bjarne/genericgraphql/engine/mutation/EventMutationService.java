package com.bjarne.genericgraphql.engine.mutation;

import com.bjarne.genericgraphql.domain.EventSourcedEntity;
import com.bjarne.genericgraphql.domain.EventType;
import com.bjarne.genericgraphql.engine.dataloader.RelationDataLoaders;
import com.bjarne.genericgraphql.engine.filter.FilterEngine;
import com.bjarne.genericgraphql.engine.meta.EntityMeta;
import com.bjarne.genericgraphql.engine.meta.MetaProvider;
import com.bjarne.genericgraphql.engine.query.EntityQueryService;
import com.bjarne.genericgraphql.engine.registry.TypeFieldType;
import com.bjarne.genericgraphql.engine.relations.RelationsFinderService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventMutationService {

    private final EntityManager entityManager;
    private final MetaProvider metaProvider;
    private final EntityQueryService queryService;
    private final RelationsFinderService relationsFinder;

    public EventMutationService(EntityManager entityManager,
                                MetaProvider metaProvider,
                                EntityQueryService queryService,
                                RelationsFinderService relationsFinder) {
        this.entityManager = entityManager;
        this.metaProvider = metaProvider;
        this.queryService = queryService;
        this.relationsFinder = relationsFinder;
    }

    public record Result(String result,
                         String message,
                         String objectBezugsId,
                         RelationsFinderService.Relations relations) {

        public static Result ok(String objectBezugsId, String message) {
            return new Result("ok", message, objectBezugsId, null);
        }

        public static Result error(String message) {
            return new Result("error", message, null, null);
        }

        public static Result blocked(String objectBezugsId,
                                     String message,
                                     RelationsFinderService.Relations relations) {
            return new Result("error", message, objectBezugsId, relations);
        }
    }

    @Transactional
    public Result handle(EntityMeta meta, Map<String, Object> data, FilterEngine.FilterContext context) {
        try {
            return doHandle(meta, data, context);
        } catch (RuntimeException ex) {
            String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            return Result.error(message);
        }
    }

    private Result doHandle(EntityMeta meta, Map<String, Object> data, FilterEngine.FilterContext context) {
        Object rawEvent = data.get("event");
        if (rawEvent == null) {
            throw new IllegalArgumentException("Feld 'event' fehlt im Input");
        }
        EventType event = rawEvent instanceof EventType type ? type : EventType.fromValue(String.valueOf(rawEvent));

        return switch (event) {
            case CREATE -> handleCreate(meta, data);
            case UPDATE, CORRECT, CLEAR -> handleChange(meta, data, event, context);
            case SNAPSHOT -> throw new IllegalArgumentException("event=snapshot darf nicht gesendet werden");
        };
    }

    // -------------------------------------------------------------------------

    private Result handleCreate(EntityMeta meta, Map<String, Object> data) {
        if (data.get("objectBezugsId") != null) {
            throw new IllegalArgumentException("objectBezugsId ist bei event=create nicht erlaubt");
        }

        EventSourcedEntity row = newInstance(meta);
        String objectId = nextObjectId(meta, row.idPrefix());
        row.setObjectId(objectId);
        row.setObjectBezugsId(objectId);
        row.setEvent(EventType.CREATE.value());
        row.setErstelltAm(OffsetDateTime.now());
        applyData(meta, row, data);
        // Der Ursprungszustand gilt "schon immer".
        row.setGueltigVon(null);

        entityManager.persist(row);
        entityManager.flush();

        rebuildSnapshots(meta, objectId);
        return Result.ok(objectId, "Objekt angelegt");
    }

    private Result handleChange(EntityMeta meta,
                                Map<String, Object> data,
                                EventType event,
                                FilterEngine.FilterContext context) {

        Object rawId = data.get("objectBezugsId");
        if (rawId == null || String.valueOf(rawId).isBlank()) {
            throw new IllegalArgumentException("objectBezugsId ist fuer event=" + event.value() + " erforderlich");
        }
        String objectBezugsId = String.valueOf(rawId);

        Object current = loadLatest(meta, objectBezugsId, context);
        if (current == null) {
            throw new IllegalArgumentException(
                    "Kein gueltiges " + meta.registration().typeName() + " zu objectBezugsId " + objectBezugsId);
        }

        if (Boolean.TRUE.equals(data.get("deleted"))) {
            var blocking = relationsFinder.findReverse(meta, current, context);
            if (!blocking.isEmpty()) {
                String ids = blocking.values().stream()
                        .map(RelationsFinderService.RelationRef::id)
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("");
                return Result.blocked(objectBezugsId,
                        "Objekt " + objectBezugsId + " kann nicht geloescht werden, es haengen noch Objekte daran: " + ids,
                        relationsFinder.find(meta, current, context));
            }
        }

        EventSourcedEntity row = newInstance(meta);
        row.setObjectId(nextObjectId(meta, row.idPrefix()));
        row.setObjectBezugsId(objectBezugsId);
        row.setEvent(event.value());
        row.setErstelltAm(OffsetDateTime.now());
        applyData(meta, row, data);

        if (event == EventType.CLEAR) {
            String clearedField = clearedFieldName(meta, data);
            row.setClearedField(clearedField);
        }

        entityManager.persist(row);
        entityManager.flush();

        rebuildSnapshots(meta, objectBezugsId);
        return Result.ok(objectBezugsId, "Event " + event.value() + " verarbeitet");
    }

    private String clearedFieldName(EntityMeta meta, Map<String, Object> data) {
        Object raw = data.get("clearedFieldName");
        if (raw == null || String.valueOf(raw).isBlank()) {
            throw new IllegalArgumentException("clearedFieldName ist fuer event=clear erforderlich");
        }
        String name = String.valueOf(raw);
        if (!meta.hasAttribute(name)) {
            throw new IllegalArgumentException("Feld '" + name + "' existiert nicht an "
                    + meta.registration().typeName());
        }
        return name;
    }

    // -------------------------------------------------------------------------
    // Snapshot-Neuaufbau
    // -------------------------------------------------------------------------

    private void rebuildSnapshots(EntityMeta meta, String objectBezugsId) {
        List<EventSourcedEntity> events = loadRows(meta, objectBezugsId, false);
        deleteSnapshots(meta, objectBezugsId);

        events.sort(Comparator
                .comparing(EventSourcedEntity::getGueltigVon, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(EventSourcedEntity::getObjectId));

        Set<LocalDate> timestamps = new LinkedHashSet<>();
        for (EventSourcedEntity event : events) {
            timestamps.add(event.getGueltigVon());
        }

        Map<String, Object> state = new LinkedHashMap<>();
        boolean deleted = false;
        int snapshotIndex = 0;

        for (LocalDate timestamp : timestamps) {
            for (EventSourcedEntity event : events) {
                if (!java.util.Objects.equals(event.getGueltigVon(), timestamp)) {
                    continue;
                }
                if (EventType.CLEAR.value().equals(event.getEvent()) && event.getClearedField() != null) {
                    state.put(event.getClearedField(), null);
                }
                for (String attribute : mutableAttributes(meta)) {
                    Object value = RelationDataLoaders.readAttribute(event, attribute);
                    if (value != null) {
                        state.put(attribute, value);
                    }
                }
                if (Boolean.TRUE.equals(event.getDeleted())) {
                    deleted = true;
                }
                if (event.getAktiv() != null) {
                    state.put("aktiv", event.getAktiv());
                }
            }

            EventSourcedEntity snapshot = newInstance(meta);
            snapshot.setObjectId(objectBezugsId + "#S" + (++snapshotIndex));
            snapshot.setObjectBezugsId(objectBezugsId);
            snapshot.setEvent(EventType.SNAPSHOT_VALUE);
            snapshot.setGueltigVon(timestamp);
            snapshot.setErstelltAm(OffsetDateTime.now());
            snapshot.setDeleted(deleted);
            for (Map.Entry<String, Object> entry : state.entrySet()) {
                writeAttribute(snapshot, entry.getKey(), entry.getValue());
            }
            snapshot.setDeleted(deleted);
            entityManager.persist(snapshot);
        }
        entityManager.flush();
    }

    private List<String> mutableAttributes(EntityMeta meta) {
        List<String> names = new ArrayList<>();
        for (EntityMeta.BasicAttr attr : meta.basicAttributes()) {
            if (isTechnical(attr.name())) {
                continue;
            }
            names.add(attr.name());
        }
        for (EntityMeta.ForwardRel rel : meta.forwardRelations()) {
            names.add(rel.name());
        }
        names.add("changedBy");
        return names;
    }

    private static boolean isTechnical(String name) {
        return switch (name) {
            case "objectId", "objectBezugsId", "event", "clearedField", "erstelltAm", "deleted" -> true;
            default -> false;
        };
    }

    @SuppressWarnings("unchecked")
    private List<EventSourcedEntity> loadRows(EntityMeta meta, String objectBezugsId, boolean snapshots) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Object> query = (CriteriaQuery<Object>) cb.createQuery(meta.entityClass());
        Root<Object> root = (Root<Object>) query.from(meta.entityClass());
        query.select(root).where(cb.and(
                cb.equal(root.get("objectBezugsId"), objectBezugsId),
                snapshots
                        ? cb.equal(root.get("event"), EventType.SNAPSHOT_VALUE)
                        : cb.notEqual(root.get("event"), EventType.SNAPSHOT_VALUE)));
        return new ArrayList<>(entityManager.createQuery(query)
                .getResultList().stream().map(EventSourcedEntity.class::cast).toList());
    }

    private void deleteSnapshots(EntityMeta meta, String objectBezugsId) {
        for (EventSourcedEntity snapshot : loadRows(meta, objectBezugsId, true)) {
            entityManager.remove(snapshot);
        }
        entityManager.flush();
    }

    // -------------------------------------------------------------------------
    // Helper-Methods
    // -------------------------------------------------------------------------

    private Object loadLatest(EntityMeta meta, String objectBezugsId, FilterEngine.FilterContext context) {
        List<Object> rows = queryService.find(new EntityQueryService.QuerySpec(
                meta, null, null, null, 1, null, context,
                (cb, root, query) -> cb.equal(root.get("objectBezugsId"), objectBezugsId)));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void applyData(EntityMeta meta, EventSourcedEntity row, Map<String, Object> data) {
        for (EntityMeta.BasicAttr attr : meta.basicAttributes()) {
            if (!meta.registration().includes(attr.name(), TypeFieldType.INPUT)) {
                continue;
            }
            if ("event".equals(attr.name()) || "objectId".equals(attr.name())) {
                continue;
            }
            if (!data.containsKey(attr.name())) {
                continue;
            }
            Object value = data.get(attr.name());
            if ("".equals(value)) {
                value = null;
            }
            writeAttribute(row, attr.name(), value);
        }

        for (EntityMeta.ForwardRel rel : meta.forwardRelations()) {
            if (!data.containsKey(rel.name())) {
                continue;
            }
            Object rawId = data.get(rel.name());
            if (rawId == null || String.valueOf(rawId).isBlank()) {
                continue;
            }
            Object target = resolveRelationTarget(rel, String.valueOf(rawId));
            writeAttribute(row, rel.name(), target);
        }
    }

    private Object resolveRelationTarget(EntityMeta.ForwardRel rel, String id) {
        EntityMeta targetMeta = metaProvider.meta(rel.targetClass());
        Object key = convertId(targetMeta, id);
        Object target = entityManager.find(rel.targetClass(), key);
        if (target == null) {
            throw new IllegalArgumentException("Kein " + targetMeta.registration().typeName()
                    + " mit " + rel.targetIdAttribute() + " = " + id);
        }
        return target;
    }

    private Object convertId(EntityMeta targetMeta, String id) {
        Class<?> idType = targetMeta.entityType().getIdType().getJavaType();
        if (idType == Long.class || idType == long.class) {
            return Long.valueOf(id);
        }
        if (idType == Integer.class || idType == int.class) {
            return Integer.valueOf(id);
        }
        return id;
    }

    private String nextObjectId(EntityMeta meta, String prefix) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<?> root = query.from(meta.entityClass());
        query.select(cb.count(root));
        long count = entityManager.createQuery(query).getSingleResult();
        String candidate;
        long next = count + 1;
        do {
            candidate = prefix + String.format("%06d", next++);
        } while (entityManager.find(meta.entityClass(), candidate) != null);
        return candidate;
    }

    private EventSourcedEntity newInstance(EntityMeta meta) {
        try {
            return (EventSourcedEntity) meta.entityClass().getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Kann " + meta.entityClass().getSimpleName() + " nicht instanziieren", ex);
        }
    }

    static void writeAttribute(Object entity, String attribute, Object value) {
        String setter = "set" + Character.toUpperCase(attribute.charAt(0)) + attribute.substring(1);
        for (Method method : entity.getClass().getMethods()) {
            if (method.getName().equals(setter) && method.getParameterCount() == 1) {
                try {
                    method.invoke(entity, coerce(method.getParameterTypes()[0], value));
                    return;
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException("Kann " + attribute + " nicht setzen", ex);
                }
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object coerce(Class<?> targetType, Object value) {
        if (value == null || targetType.isInstance(value)) {
            return value;
        }
        if (targetType.isEnum() && value instanceof String text) {
            return Enum.valueOf((Class<? extends Enum>) targetType, text);
        }
        return value;
    }
}
