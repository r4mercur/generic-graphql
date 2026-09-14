package com.bjarne.genericgraphql.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "gql_photo")
public class Photo extends EventSourcedEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "profile_id")
    private Profile profile;

    @Column(name = "url", length = 500)
    private String url;

    @Column(name = "caption", length = 300)
    private String caption;

    @Column(name = "sort_order")
    private Integer sortOrder;

    @Column(name = "primary_photo")
    private Boolean primaryPhoto = Boolean.FALSE;

    @Override
    public String idPrefix() {
        return "PHOT";
    }

    public Profile getProfile() {
        return profile;
    }

    public void setProfile(Profile profile) {
        this.profile = profile;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getCaption() {
        return caption;
    }

    public void setCaption(String caption) {
        this.caption = caption;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Boolean getPrimaryPhoto() {
        return primaryPhoto;
    }

    public void setPrimaryPhoto(Boolean primaryPhoto) {
        this.primaryPhoto = primaryPhoto;
    }
}
