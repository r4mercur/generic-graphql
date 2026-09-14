package com.bjarne.genericgraphql.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "gql_interest")
public class Interest extends EventSourcedEntity {

    @Column(name = "name", length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 30)
    private InterestCategory category;

    @Column(name = "description", length = 1000)
    private String description;

    @OneToMany(mappedBy = "interest", fetch = FetchType.LAZY)
    private List<ProfileInterest> profileInterests = new ArrayList<>();

    @Override
    public String idPrefix() {
        return "INTR";
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public InterestCategory getCategory() {
        return category;
    }

    public void setCategory(InterestCategory category) {
        this.category = category;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<ProfileInterest> getProfileInterests() {
        return profileInterests;
    }
}
