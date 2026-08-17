package com.etp.platform.capability.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Schema(description = "Lifecycle transition for a specific capability version")
public class VersionStatusUpdate {

    @NotNull
    @Schema(description = "Capability version ID")
    private UUID versionId;

    @NotBlank
    @Schema(description = "Target version lifecycle status", example = "VALIDATED")
    private String status;

    public UUID getVersionId() {
        return versionId;
    }

    public void setVersionId(UUID versionId) {
        this.versionId = versionId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
