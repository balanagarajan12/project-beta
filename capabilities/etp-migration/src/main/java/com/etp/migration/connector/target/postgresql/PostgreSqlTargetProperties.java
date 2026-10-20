package com.etp.migration.connector.target.postgresql;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "etp.migration.target.postgresql")
public record PostgreSqlTargetProperties(
		String url,
		String username,
		String password,
		String schema,
		Integer maxPoolSize,
		Pool pool
) {

	@ConstructorBinding
	public PostgreSqlTargetProperties(
			String url,
			String username,
			String password,
			String schema,
			Integer maxPoolSize,
			@DefaultValue Pool pool) {
		this.url = url;
		this.username = username;
		this.password = password;
		this.schema = schema;
		this.maxPoolSize = maxPoolSize;
		this.pool = pool == null ? Pool.defaults() : pool;
	}

	public PostgreSqlTargetProperties(String url, String username, String password, String schema, Integer maxPoolSize) {
		this(url, username, password, schema, maxPoolSize, Pool.defaults());
	}

	public record Pool(
			@DefaultValue("10s") Duration connectionTimeout,
			@DefaultValue("5s") Duration validationTimeout,
			@DefaultValue("10m") Duration idleTimeout,
			@DefaultValue("30m") Duration maxLifetime,
			@DefaultValue("2m") Duration keepaliveTime
	) {

		public static Pool defaults() {
			return new Pool(
					Duration.ofSeconds(10),
					Duration.ofSeconds(5),
					Duration.ofMinutes(10),
					Duration.ofMinutes(30),
					Duration.ofMinutes(2));
		}
	}
}
