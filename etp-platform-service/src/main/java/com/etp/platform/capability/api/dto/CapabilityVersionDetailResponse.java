package com.etp.platform.capability.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(description = "Capability version with contract, implementation, and dependencies")
public class CapabilityVersionDetailResponse {

    @Schema(description = "Capability version ID")
    private UUID id;

    @Schema(description = "Semantic version")
    private String version;

    @Schema(description = "Version lifecycle status")
    private String status;

    @Schema(description = "Version description")
    private String description;

    @Schema(description = "Version contract")
    private ContractResponse contract;

    @Schema(description = "Version implementation")
    private ImplementationResponse implementation;

    @Schema(description = "Version dependencies")
    private List<DependencyResponse> dependencies;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public ContractResponse getContract() {
        return contract;
    }

    public void setContract(ContractResponse contract) {
        this.contract = contract;
    }

    public ImplementationResponse getImplementation() {
        return implementation;
    }

    public void setImplementation(ImplementationResponse implementation) {
        this.implementation = implementation;
    }

    public List<DependencyResponse> getDependencies() {
        return dependencies;
    }

    public void setDependencies(List<DependencyResponse> dependencies) {
        this.dependencies = dependencies;
    }
}
