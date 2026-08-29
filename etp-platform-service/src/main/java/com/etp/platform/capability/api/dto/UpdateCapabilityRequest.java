package com.etp.platform.capability.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Full capability metadata replacement (code is immutable after creation)")
public class UpdateCapabilityRequest {

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

    @NotBlank
    @Size(max = 30)
    @Schema(description = "Capability lifecycle status", example = "ACTIVE")
    private String status;

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
}
