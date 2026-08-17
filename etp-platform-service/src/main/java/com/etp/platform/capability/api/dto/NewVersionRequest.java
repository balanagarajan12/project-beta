package com.etp.platform.capability.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

@Schema(description = "Explicit new capability version with contract, implementation, and dependencies")
public class NewVersionRequest extends VersionRequest {

    @NotNull
    @Valid
    @Schema(description = "Contract for the new version")
    private ContractRequest contract;

    @Valid
    @Schema(description = "Optional implementation for the new version")
    private ImplementationRequest implementation;

    @Valid
    @Schema(description = "Optional dependencies for the new version")
    private List<DependencyRequest> dependencies;

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
