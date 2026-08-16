package com.etp.platform.application;

import com.etp.platform.capability.CapabilityVersion;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "application_capability")
public class ApplicationCapability {

    @Id
    @Column(name = "application_capability_id", nullable = false)
    private UUID applicationCapabilityId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_definition_id", nullable = false)
    private ApplicationDefinition applicationDefinition;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "capability_version_id", nullable = false)
    private CapabilityVersion capabilityVersion;

    @Column(name = "usage_type", nullable = false, length = 30)
    private String usageType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "configuration", columnDefinition = "jsonb")
    private String configuration;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    public UUID getApplicationCapabilityId() {
        return applicationCapabilityId;
    }

    public void setApplicationCapabilityId(UUID applicationCapabilityId) {
        this.applicationCapabilityId = applicationCapabilityId;
    }

    public ApplicationDefinition getApplicationDefinition() {
        return applicationDefinition;
    }

    public void setApplicationDefinition(ApplicationDefinition applicationDefinition) {
        this.applicationDefinition = applicationDefinition;
    }

    public CapabilityVersion getCapabilityVersion() {
        return capabilityVersion;
    }

    public void setCapabilityVersion(CapabilityVersion capabilityVersion) {
        this.capabilityVersion = capabilityVersion;
    }

    public String getUsageType() {
        return usageType;
    }

    public void setUsageType(String usageType) {
        this.usageType = usageType;
    }

    public String getConfiguration() {
        return configuration;
    }

    public void setConfiguration(String configuration) {
        this.configuration = configuration;
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

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }
}
