package com.etp.migration.connector.target.postgresql;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "etp.migration.target.postgresql")
public record PostgreSqlTargetProperties(
		String url,
		String username,
		String password,
		String schema,
		Integer maxPoolSize
) {
}
