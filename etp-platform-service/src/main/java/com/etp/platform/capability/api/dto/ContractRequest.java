package com.etp.platform.capability.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Capability contract — inline JSON document or external artifact reference")
public class ContractRequest {

    @NotBlank
    @Size(max = 50)
    @Schema(description = "Contract type", example = "COMPOSITE")
    private String contractType;

    @NotBlank
    @Size(max = 50)
    @Schema(description = "Contract schema/version identifier", example = "1.0")
    private String contractVersion;

    @NotBlank
    @Size(max = 30)
    @Schema(description = "Contract format", example = "JSON")
    private String contractFormat;

    @Schema(description = "Inline structured contract document (JSONB)")
    private Object document;

    @Size(max = 500)
    @Schema(description = "External contract artifact location (OpenAPI, AsyncAPI, etc.)")
    private String location;

    public String getContractType() {
        return contractType;
    }

    public void setContractType(String contractType) {
        this.contractType = contractType;
    }

    public String getContractVersion() {
        return contractVersion;
    }

    public void setContractVersion(String contractVersion) {
        this.contractVersion = contractVersion;
    }

    public String getContractFormat() {
        return contractFormat;
    }

    public void setContractFormat(String contractFormat) {
        this.contractFormat = contractFormat;
    }

    public Object getDocument() {
        return document;
    }

    public void setDocument(Object document) {
        this.document = document;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public boolean hasInlineDocument() {
        return document != null;
    }

    public boolean hasExternalLocation() {
        return location != null && !location.isBlank();
    }
}
