package com.etp.platform.capability.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Capability version dependency")
public class DependencyResponse {

    @Schema(description = "Depended-on capability version ID")
    private UUID capabilityVersionId;

    @Schema(description = "Dependency type")
    private String dependencyType;

    @Schema(description = "Whether the dependency is mandatory")
    private boolean mandatory;

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

    public boolean isMandatory() {
        return mandatory;
    }

    public void setMandatory(boolean mandatory) {
        this.mandatory = mandatory;
    }
}
