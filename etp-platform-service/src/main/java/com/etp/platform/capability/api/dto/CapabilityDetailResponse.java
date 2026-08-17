package com.etp.platform.capability.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(description = "Complete capability aggregate")
public class CapabilityDetailResponse {

    @Schema(description = "Capability ID")
    private UUID id;

    @Schema(description = "Capability code")
    private String code;

    @Schema(description = "Capability name")
    private String name;

    @Schema(description = "Capability description")
    private String description;

    @Schema(description = "Capability category")
    private String category;

    @Schema(description = "Capability lifecycle status")
    private String status;

    @Schema(description = "Capability versions")
    private List<CapabilityVersionDetailResponse> versions;

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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public List<CapabilityVersionDetailResponse> getVersions() {
        return versions;
    }

    public void setVersions(List<CapabilityVersionDetailResponse> versions) {
        this.versions = versions;
    }
}
