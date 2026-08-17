package com.etp.platform.capability.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Lightweight capability summary for collection responses")
public class CapabilitySummaryResponse {

    @Schema(description = "Capability ID")
    private UUID id;

    @Schema(description = "Capability code", example = "etp.iam")
    private String code;

    @Schema(description = "Capability name")
    private String name;

    @Schema(description = "Capability category")
    private String category;

    @Schema(description = "Capability lifecycle status")
    private String status;

    @Schema(description = "Latest registered version")
    private String latestVersion;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

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

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getLatestVersion() {
        return latestVersion;
    }

    public void setLatestVersion(String latestVersion) {
        this.latestVersion = latestVersion;
    }
}
