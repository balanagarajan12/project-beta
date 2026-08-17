package com.etp.platform.capability.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Capability implementation details")
public class ImplementationResponse {

    @Schema(description = "Implementation code")
    private String code;

    @Schema(description = "Implementation name")
    private String name;

    @Schema(description = "Technology name")
    private String technology;

    @Schema(description = "Technology version")
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
