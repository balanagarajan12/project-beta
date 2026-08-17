package com.etp.platform.capability.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Technology implementation behind a capability version")
public class ImplementationRequest {

    @NotBlank
    @Size(max = 100)
    @Schema(description = "Implementation code", example = "etp-iam")
    private String code;

    @NotBlank
    @Size(max = 200)
    @Schema(description = "Implementation name", example = "ETP IAM Service")
    private String name;

    @NotBlank
    @Size(max = 200)
    @Schema(description = "Underlying technology", example = "Keycloak")
    private String technology;

    @Size(max = 100)
    @Schema(description = "Technology version", example = "26.x")
    private String technologyVersion;

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

    public String getTechnology() {
        return technology;
    }

    public void setTechnology(String technology) {
        this.technology = technology;
    }

    public String getTechnologyVersion() {
        return technologyVersion;
    }

    public void setTechnologyVersion(String technologyVersion) {
        this.technologyVersion = technologyVersion;
    }
}
