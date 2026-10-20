package com.etp.export.dto;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Describes the external API to call and the file to produce.
 *
 * @param path        full URL of the external API, e.g. "http://order-service:8080/api/v1/orders?status=OPEN"
 *                    (for GET everything is in the path)
 * @param httpMethod  GET, POST, PUT, PATCH or DELETE
 * @param body        JSON body (columns, filters, ...) sent as-is to the external API for POST/PUT/PATCH
 * @param fileType    CSV or XLSX
 * @param headers     optional headers forwarded to the external API (Authorization, tenant id, ...)
 * @param fileName    optional download file name without extension
 * @param sheetName   optional XLSX sheet name
 */
public record ExportRequest(
        @NotBlank String path,
        @NotBlank
        @Pattern(regexp = "(?i)GET|POST|PUT|PATCH|DELETE", message = "must be one of GET, POST, PUT, PATCH, DELETE")
        String httpMethod,
        Object body,
        @NotNull FileType fileType,
        Map<String, String> headers,
        String fileName,
        String sheetName) {
}
