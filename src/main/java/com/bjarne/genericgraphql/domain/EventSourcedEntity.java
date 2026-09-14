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

    @Column(name = "object_bezugs_id", length = 64)
    private String objectBezugsId;

    @Column(name = "gueltig_von")
    private LocalDate gueltigVon;

    @Column(name = "event", length = 32)
    private String event;

    @Column(name = "cleared_field", length = 140)
    private String clearedField;

    @Column(name = "note", length = 1000)
    private String note;

    @Column(name = "aktiv")
    private Boolean aktiv = Boolean.TRUE;

    @Column(name = "deleted")
    private Boolean deleted = Boolean.FALSE;

    @Column(name = "erstellt_am")
    private OffsetDateTime erstelltAm;

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

    public String getObjectBezugsId() {
        return objectBezugsId;
    }

    public void setObjectBezugsId(String objectBezugsId) {
        this.objectBezugsId = objectBezugsId;
    }

    public LocalDate getGueltigVon() {
        return gueltigVon;
    }

    public void setGueltigVon(LocalDate gueltigVon) {
        this.gueltigVon = gueltigVon;
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

    public Boolean getAktiv() {
        return aktiv;
    }

    public void setAktiv(Boolean aktiv) {
        this.aktiv = aktiv;
    }

    public Boolean getDeleted() {
        return deleted;
    }

    public void setDeleted(Boolean deleted) {
        this.deleted = deleted;
    }

    public OffsetDateTime getErstelltAm() {
        return erstelltAm;
    }

    public void setErstelltAm(OffsetDateTime erstelltAm) {
        this.erstelltAm = erstelltAm;
    }

    public AppUser getChangedBy() {
        return changedBy;
    }

    public void setChangedBy(AppUser changedBy) {
        this.changedBy = changedBy;
    }
}
