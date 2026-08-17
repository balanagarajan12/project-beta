package com.etp.platform.capability.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Capability contract metadata and content")
public class ContractResponse {

    @Schema(description = "Contract type")
    private String contractType;

    @Schema(description = "Contract version")
    private String contractVersion;

    @Schema(description = "Contract format")
    private String contractFormat;

    @Schema(description = "Inline contract document when present")
    private Object document;

    @Schema(description = "External contract location when present")
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
}
