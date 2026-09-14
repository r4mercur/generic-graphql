package com.bjarne.genericgraphql.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "gql_match")
public class Match extends EventSourcedEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "profile_a_id")
    private Profile profileA;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "profile_b_id")
    private Profile profileB;

    @Column(name = "matched_at")
    private OffsetDateTime matchedAt;

    @Column(name = "score", precision = 19, scale = 4)
    private BigDecimal score;

    @Column(name = "closed")
    private Boolean closed = Boolean.FALSE;

    @OneToMany(mappedBy = "matchRef", fetch = FetchType.LAZY)
    private List<Message> messages = new ArrayList<>();

    @Override
    public String idPrefix() {
        return "MTCH";
    }

    public Profile getProfileA() {
        return profileA;
    }

    public void setProfileA(Profile profileA) {
        this.profileA = profileA;
    }

    public Profile getProfileB() {
        return profileB;
    }

    public void setProfileB(Profile profileB) {
        this.profileB = profileB;
    }

    public OffsetDateTime getMatchedAt() {
        return matchedAt;
    }

    public void setMatchedAt(OffsetDateTime matchedAt) {
        this.matchedAt = matchedAt;
    }

    public BigDecimal getScore() {
        return score;
    }

    public void setScore(BigDecimal score) {
        this.score = score;
    }

    public Boolean getClosed() {
        return closed;
    }

    public void setClosed(Boolean closed) {
        this.closed = closed;
    }

    public List<Message> getMessages() {
        return messages;
    }
}
