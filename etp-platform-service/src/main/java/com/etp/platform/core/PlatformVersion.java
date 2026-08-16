package com.etp.platform.core;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "platform_version")
public class PlatformVersion {

    @Id
    @Column(name = "platform_version_id", nullable = false)
    private UUID platformVersionId;

    @Column(name = "version", nullable = false, length = 50)
    private String version;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "release_date")
    private Instant releaseDate;

    @Column(name = "release_notes")
    private String releaseNotes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID getPlatformVersionId() {
        return platformVersionId;
    }

    public void setPlatformVersionId(UUID platformVersionId) {
        this.platformVersionId = platformVersionId;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getReleaseDate() {
        return releaseDate;
    }

    public void setReleaseDate(Instant releaseDate) {
        this.releaseDate = releaseDate;
    }

    public String getReleaseNotes() {
        return releaseNotes;
    }

    public void setReleaseNotes(String releaseNotes) {
        this.releaseNotes = releaseNotes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
