package com.etp.migration.connector.target.uam;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "etp.migration.target.uam")
public record UamTargetProperties(
		String baseUrl,
		String path,
		Duration connectTimeout,
		Duration readTimeout,
		String tokenUri,
		String clientId,
		String clientSecret,
		String scope
) {
}
