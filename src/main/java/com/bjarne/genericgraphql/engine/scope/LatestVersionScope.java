package com.bjarne.genericgraphql.engine.scope;

import com.bjarne.genericgraphql.domain.EventType;
import com.bjarne.genericgraphql.engine.meta.EntityMeta;
import jakarta.persistence.criteria.AbstractQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

@Component
public class LatestVersionScope {

    public Predicate scopeToLatest(CriteriaBuilder cb,
                                   AbstractQuery<?> query,
                                   From<?, ?> root,
                                   EntityMeta meta,
                                   LocalDate date) {

        Predicate base = snapshotPredicate(cb, root, date);

        Subquery<String> newer = query.subquery(String.class);
        @SuppressWarnings("unchecked")
        Root<Object> other = (Root<Object>) newer.from(meta.entityClass());
        newer.select(other.get("objectId"));

        Predicate sameObject = cb.equal(
                other.get("objectBezugsId"), root.get("objectBezugsId"));
        Predicate otherIsSnapshot = snapshotPredicate(cb, other, date);
        Predicate otherIsNewer = isNewer(cb, other, root);

        newer.where(cb.and(sameObject, otherIsSnapshot, otherIsNewer));

        return cb.and(base, cb.not(cb.exists(newer)));
    }

    private Predicate snapshotPredicate(CriteriaBuilder cb, From<?, ?> path, LocalDate date) {
        Predicate isSnapshot = cb.equal(path.get("event"), EventType.SNAPSHOT_VALUE);
        Predicate notDeleted = cb.or(
                cb.isNull(path.get("deleted")),
                cb.isFalse(path.get("deleted")));

        Predicate validAt;
        if (date == null) {
            validAt = cb.isNull(path.get("gueltigVon"));
        } else {
            validAt = cb.or(
                    cb.isNull(path.get("gueltigVon")),
                    cb.lessThanOrEqualTo(path.<LocalDate>get("gueltigVon"), date));
        }
        return cb.and(isSnapshot, notDeleted, validAt);
    }

    private Predicate isNewer(CriteriaBuilder cb, From<?, ?> other, From<?, ?> root) {
        var otherDate = other.<LocalDate>get("gueltigVon");
        var rootDate = root.<LocalDate>get("gueltigVon");

        Predicate otherDatedRootNot = cb.and(cb.isNotNull(otherDate), cb.isNull(rootDate));
        Predicate strictlyLater = cb.and(
                cb.isNotNull(otherDate),
                cb.isNotNull(rootDate),
                cb.greaterThan(otherDate, rootDate));
        Predicate tieBreak = cb.and(
                cb.or(
                        cb.and(cb.isNull(otherDate), cb.isNull(rootDate)),
                        cb.and(cb.isNotNull(otherDate), cb.isNotNull(rootDate),
                                cb.equal(otherDate, rootDate))),
                cb.greaterThan(other.<String>get("objectId"), root.<String>get("objectId")));

        return cb.or(otherDatedRootNot, strictlyLater, tieBreak);
    }
}
