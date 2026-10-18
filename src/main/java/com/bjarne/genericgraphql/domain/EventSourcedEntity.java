package com.bjarne.genericgraphql.domain;

import jakarta.persistence.Column;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@MappedSuperclass
public abstract class EventSourcedEntity {

    @Id
    @Column(name = "object_id", length = 64, nullable = false)
    private String objectId;

    @Column(name = "object_ref_id", length = 64)
    private String objectRefId;

    @Column(name = "valid_from")
    private LocalDate validFrom;

    @Column(name = "event", length = 32)
    private String event;

    @Column(name = "cleared_field", length = 140)
    private String clearedField;

    @Column(name = "note", length = 1000)
    private String note;

    @Column(name = "active")
    private Boolean active = Boolean.TRUE;

    @Column(name = "deleted")
    private Boolean deleted = Boolean.FALSE;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "changed_by")
    private AppUser changedBy;

    public abstract String idPrefix();

    public String getObjectId() {
        return objectId;
    }

    public void setObjectId(String objectId) {
        this.objectId = objectId;
    }

    public String getObjectRefId() {
        return objectRefId;
    }

    public void setObjectRefId(String objectRefId) {
        this.objectRefId = objectRefId;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public void setValidFrom(LocalDate validFrom) {
        this.validFrom = validFrom;
    }

    public String getEvent() {
        return event;
    }

    public void setEvent(String event) {
        this.event = event;
    }

    public String getClearedField() {
        return clearedField;
    }

    public void setClearedField(String clearedField) {
        this.clearedField = clearedField;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public Boolean getDeleted() {
        return deleted;
    }

    public void setDeleted(Boolean deleted) {
        this.deleted = deleted;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public AppUser getChangedBy() {
        return changedBy;
    }

    public void setChangedBy(AppUser changedBy) {
        this.changedBy = changedBy;
    }
}
