package com.etp.platform.capability.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;

@Schema(description = "Partial update to a capability — metadata, lifecycle, new version, or version lifecycle")
public class PatchCapabilityRequest {

    @Schema(description = "Updated capability name")
    private String name;

    @Schema(description = "Updated capability description")
    private String description;

    @Schema(description = "Capability lifecycle status transition", example = "DEPRECATED")
    private String status;

    @Valid
    @Schema(description = "Explicit new capability version creation")
    private NewVersionRequest newVersion;

    @Valid
    @Schema(description = "Capability version lifecycle status transition")
    private VersionStatusUpdate versionStatus;

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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public NewVersionRequest getNewVersion() {
        return newVersion;
    }

    public void setNewVersion(NewVersionRequest newVersion) {
        this.newVersion = newVersion;
    }

    public VersionStatusUpdate getVersionStatus() {
        return versionStatus;
    }

    public void setVersionStatus(VersionStatusUpdate versionStatus) {
        this.versionStatus = versionStatus;
    }
}
