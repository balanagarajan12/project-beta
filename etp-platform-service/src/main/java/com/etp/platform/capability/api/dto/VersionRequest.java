package com.etp.platform.capability.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Capability version metadata")
public class VersionRequest {

    @NotBlank
    @Size(max = 50)
    @Pattern(
            regexp = "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?$",
            message = "must be a valid semantic version (e.g. 1.0.0)")
    @Schema(description = "Semantic version", example = "1.0.0")
    private String version;

    @Size(max = 5000)
    @Schema(description = "Version description / release notes")
    private String description;

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
