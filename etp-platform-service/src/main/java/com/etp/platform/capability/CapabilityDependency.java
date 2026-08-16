package com.etp.platform.capability;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "capability_dependency")
public class CapabilityDependency {

    @Id
    @Column(name = "dependency_id", nullable = false)
    private UUID dependencyId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "capability_version_id", nullable = false)
    private CapabilityVersion capabilityVersion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "depends_on_capability_version_id", nullable = false)
    private CapabilityVersion dependsOnCapabilityVersion;

    @Column(name = "dependency_type", nullable = false, length = 30)
    private String dependencyType;

    @Column(name = "mandatory", nullable = false)
    private boolean mandatory;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID getDependencyId() {
        return dependencyId;
    }

    public void setDependencyId(UUID dependencyId) {
        this.dependencyId = dependencyId;
    }

    public CapabilityVersion getCapabilityVersion() {
        return capabilityVersion;
    }

    public void setCapabilityVersion(CapabilityVersion capabilityVersion) {
        this.capabilityVersion = capabilityVersion;
    }

    public CapabilityVersion getDependsOnCapabilityVersion() {
        return dependsOnCapabilityVersion;
    }

    public void setDependsOnCapabilityVersion(CapabilityVersion dependsOnCapabilityVersion) {
        this.dependsOnCapabilityVersion = dependsOnCapabilityVersion;
    }

    public String getDependencyType() {
        return dependencyType;
    }

    public void setDependencyType(String dependencyType) {
        this.dependencyType = dependencyType;
    }

    public boolean isMandatory() {
        return mandatory;
    }

    public void setMandatory(boolean mandatory) {
        this.mandatory = mandatory;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
