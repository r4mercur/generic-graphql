package com.bjarne.genericgraphql.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "gql_profile")
public class Profile extends EventSourcedEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private AppUser owner;

    @Column(name = "display_name", length = 120)
    private String displayName;

    @Column(name = "bio", length = 2000)
    private String bio;

    @Column(name = "city", length = 120)
    private String city;

    @Column(name = "country", length = 2)
    private String country;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(name = "height_cm")
    private Integer heightCm;

    @Enumerated(EnumType.STRING)
    @Column(name = "looking_for", length = 20)
    private Gender lookingFor;

    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;

    @Column(name = "last_active_at")
    private OffsetDateTime lastActiveAt;

    @Column(name = "ranking_score", precision = 19, scale = 4)
    private BigDecimal rankingScore;

    @OneToMany(mappedBy = "profile", fetch = FetchType.LAZY)
    private List<Photo> photos = new ArrayList<>();

    @OneToMany(mappedBy = "profile", fetch = FetchType.LAZY)
    private List<ProfileInterest> profileInterests = new ArrayList<>();

    @OneToMany(mappedBy = "profileA", fetch = FetchType.LAZY)
    private List<Match> matchesAsA = new ArrayList<>();

    @OneToMany(mappedBy = "profileB", fetch = FetchType.LAZY)
    private List<Match> matchesAsB = new ArrayList<>();

    @OneToMany(mappedBy = "sender", fetch = FetchType.LAZY)
    private List<Message> sentMessages = new ArrayList<>();

    @Override
    public String idPrefix() {
        return "PROF";
    }

    public AppUser getOwner() {
        return owner;
    }

    public void setOwner(AppUser owner) {
        this.owner = owner;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public void setBirthDate(LocalDate birthDate) {
        this.birthDate = birthDate;
    }

    public Integer getHeightCm() {
        return heightCm;
    }

    public void setHeightCm(Integer heightCm) {
        this.heightCm = heightCm;
    }

    public Gender getLookingFor() {
        return lookingFor;
    }

    public void setLookingFor(Gender lookingFor) {
        this.lookingFor = lookingFor;
    }

    public OffsetDateTime getVerifiedAt() {
        return verifiedAt;
    }

    public void setVerifiedAt(OffsetDateTime verifiedAt) {
        this.verifiedAt = verifiedAt;
    }

    public OffsetDateTime getLastActiveAt() {
        return lastActiveAt;
    }

    public void setLastActiveAt(OffsetDateTime lastActiveAt) {
        this.lastActiveAt = lastActiveAt;
    }

    public BigDecimal getRankingScore() {
        return rankingScore;
    }

    public void setRankingScore(BigDecimal rankingScore) {
        this.rankingScore = rankingScore;
    }

    public List<Photo> getPhotos() {
        return photos;
    }

    public List<ProfileInterest> getProfileInterests() {
        return profileInterests;
    }

    public List<Match> getMatchesAsA() {
        return matchesAsA;
    }

    public List<Match> getMatchesAsB() {
        return matchesAsB;
    }

    public List<Message> getSentMessages() {
        return sentMessages;
    }
}
