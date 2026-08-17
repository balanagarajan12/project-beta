package com.etp.platform.capability.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "Request to create a complete capability aggregate with its first version")
public class CreateCapabilityRequest {

    @NotBlank
    @Size(max = 100)
    @Pattern(regexp = "^[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)+$", message = "must be a lowercase dotted identifier (e.g. etp.iam)")
    @Schema(description = "Unique capability code", example = "etp.iam")
    private String code;

    @NotBlank
    @Size(max = 200)
    @Schema(description = "Human-readable capability name", example = "Identity & Access Management")
    private String name;

    @Size(max = 5000)
    @Schema(description = "Capability description")
    private String description;

    @NotBlank
    @Size(max = 50)
    @Schema(description = "Capability category", example = "SECURITY")
    private String category;

    @NotNull
    @Valid
    @Schema(description = "Initial capability version (first version only)")
    private VersionRequest version;

    @NotNull
    @Valid
    @Schema(description = "Capability contract for the initial version")
    private ContractRequest contract;

    @Valid
    @Schema(description = "Optional implementation details for the initial version")
    private ImplementationRequest implementation;

    @Valid
    @Schema(description = "Optional capability version dependencies")
    private List<DependencyRequest> dependencies;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public VersionRequest getVersion() {
        return version;
    }

    public void setVersion(VersionRequest version) {
        this.version = version;
    }

    public ContractRequest getContract() {
        return contract;
    }

    public void setContract(ContractRequest contract) {
        this.contract = contract;
    }

    public ImplementationRequest getImplementation() {
        return implementation;
    }

    public void setImplementation(ImplementationRequest implementation) {
        this.implementation = implementation;
    }

    public List<DependencyRequest> getDependencies() {
        return dependencies;
    }

    public void setDependencies(List<DependencyRequest> dependencies) {
        this.dependencies = dependencies;
    }
}
