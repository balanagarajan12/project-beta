package com.etp.platform.capability.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

@Schema(description = "Dependency on another capability version")
public class DependencyRequest {

    @NotNull
    @Schema(description = "ID of the depended-on capability version")
    private UUID capabilityVersionId;

    @NotBlank
    @Size(max = 30)
    @Schema(description = "Dependency type", example = "RUNTIME")
    private String dependencyType;

    @NotNull
    @Schema(description = "Whether the dependency is mandatory")
    private Boolean mandatory;

    public UUID getCapabilityVersionId() {
        return capabilityVersionId;
    }

    public void setCapabilityVersionId(UUID capabilityVersionId) {
        this.capabilityVersionId = capabilityVersionId;
    }

    public String getDependencyType() {
        return dependencyType;
    }

    public void setDependencyType(String dependencyType) {
        this.dependencyType = dependencyType;
    }

    public Boolean getMandatory() {
        return mandatory;
    }

    public void setMandatory(Boolean mandatory) {
        this.mandatory = mandatory;
    }
}
