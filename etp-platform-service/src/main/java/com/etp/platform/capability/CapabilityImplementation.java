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
@Table(name = "capability_implementation")
public class CapabilityImplementation {

    @Id
    @Column(name = "implementation_id", nullable = false)
    private UUID implementationId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "capability_version_id", nullable = false)
    private CapabilityVersion capabilityVersion;

    @Column(name = "implementation_code", nullable = false, length = 100)
    private String implementationCode;

    @Column(name = "implementation_name", nullable = false, length = 200)
    private String implementationName;

    @Column(name = "technology_name", nullable = false, length = 200)
    private String technologyName;

    @Column(name = "technology_version", length = 100)
    private String technologyVersion;

    @Column(name = "implementation_type", length = 50)
    private String implementationType;

    @Column(name = "configuration_reference", length = 500)
    private String configurationReference;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID getImplementationId() {
        return implementationId;
    }

    public void setImplementationId(UUID implementationId) {
        this.implementationId = implementationId;
    }

    public CapabilityVersion getCapabilityVersion() {
        return capabilityVersion;
    }

    public void setCapabilityVersion(CapabilityVersion capabilityVersion) {
        this.capabilityVersion = capabilityVersion;
    }

    public String getImplementationCode() {
        return implementationCode;
    }

    public void setImplementationCode(String implementationCode) {
        this.implementationCode = implementationCode;
    }

    public String getImplementationName() {
        return implementationName;
    }

    public void setImplementationName(String implementationName) {
        this.implementationName = implementationName;
    }

    public String getTechnologyName() {
        return technologyName;
    }

    public void setTechnologyName(String technologyName) {
        this.technologyName = technologyName;
    }

    public String getTechnologyVersion() {
        return technologyVersion;
    }

    public void setTechnologyVersion(String technologyVersion) {
        this.technologyVersion = technologyVersion;
    }

    public String getImplementationType() {
        return implementationType;
    }

    public void setImplementationType(String implementationType) {
        this.implementationType = implementationType;
    }

    public String getConfigurationReference() {
        return configurationReference;
    }

    public void setConfigurationReference(String configurationReference) {
        this.configurationReference = configurationReference;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
