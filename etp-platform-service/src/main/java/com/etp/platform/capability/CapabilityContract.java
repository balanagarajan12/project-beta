package com.etp.platform.capability;

import com.fasterxml.jackson.databind.JsonNode;
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
@Table(name = "capability_contract")
public class CapabilityContract {

    @Id
    @Column(name = "contract_id", nullable = false)
    private UUID contractId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "capability_version_id", nullable = false)
    private CapabilityVersion capabilityVersion;

    @Column(name = "contract_type", nullable = false, length = 50)
    private String contractType;

    @Column(name = "contract_version", nullable = false, length = 50)
    private String contractVersion;

    @Column(name = "contract_format", nullable = false, length = 30)
    private String contractFormat;

    @Column(name = "contract_location", length = 500)
    private String contractLocation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "contract_document", columnDefinition = "jsonb")
    private JsonNode contractDocument;

    @Column(name = "description")
    private String description;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UUID getContractId() {
        return contractId;
    }

    public void setContractId(UUID contractId) {
        this.contractId = contractId;
    }

    public CapabilityVersion getCapabilityVersion() {
        return capabilityVersion;
    }

    public void setCapabilityVersion(CapabilityVersion capabilityVersion) {
        this.capabilityVersion = capabilityVersion;
    }

    public String getContractType() {
        return contractType;
    }

    public void setContractType(String contractType) {
        this.contractType = contractType;
    }

    public String getContractVersion() {
        return contractVersion;
    }

    public void setContractVersion(String contractVersion) {
        this.contractVersion = contractVersion;
    }

    public String getContractFormat() {
        return contractFormat;
    }

    public void setContractFormat(String contractFormat) {
        this.contractFormat = contractFormat;
    }

    public String getContractLocation() {
        return contractLocation;
    }

    public void setContractLocation(String contractLocation) {
        this.contractLocation = contractLocation;
    }

    public JsonNode getContractDocument() {
        return contractDocument;
    }

    public void setContractDocument(JsonNode contractDocument) {
        this.contractDocument = contractDocument;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
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

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
