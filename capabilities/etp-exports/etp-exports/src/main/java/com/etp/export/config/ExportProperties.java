package com.etp.export.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code etp.export.*} in application.yml.
 *
 * @param connectTimeout   connect timeout for the external API
 * @param readTimeout      read timeout for the external API
 * @param defaultBaseUrl   used when a request has no baseUrl and a relative path
 * @param allowedHosts     hosts the service may call (supports "*.domain.com"); empty = allow all
 * @param maxRows          safety limit on rows per export
 * @param defaultSheetName sheet name used for XLSX when the request gives none
 * @param csvUtf8Bom       write a UTF-8 BOM so Excel opens CSV with the right encoding
 */
@ConfigurationProperties(prefix = "etp.export")
public record ExportProperties(
        @DefaultValue("10s") Duration connectTimeout,
        @DefaultValue("120s") Duration readTimeout,
        String defaultBaseUrl,
        @DefaultValue List<String> allowedHosts,
        @DefaultValue("1000000") int maxRows,
        @DefaultValue("Data") String defaultSheetName,
        @DefaultValue("true") boolean csvUtf8Bom) {
}
