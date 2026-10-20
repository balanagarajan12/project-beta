package com.etp.migration.connector.target.postgresql;

import com.etp.migration.config.HikariPoolSettings;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

@Configuration
@EnableConfigurationProperties(PostgreSqlTargetProperties.class)
public class PostgreSqlTargetClientConfig {

	public static final String DATASOURCE_BEAN = "postgresqlTargetDataSource";
	static final String POOL_PREFIX = "etp.migration.target.postgresql.pool";

	@Bean(name = DATASOURCE_BEAN, destroyMethod = "close")
	@Conditional(TargetUrlPresent.class)
	@Qualifier(DATASOURCE_BEAN)
	public HikariDataSource postgresqlTargetDataSource(PostgreSqlTargetProperties properties) {
		return new HikariDataSource(hikariConfig(properties));
	}

	public static HikariConfig hikariConfig(PostgreSqlTargetProperties properties) {
		HikariConfig config = new HikariConfig();
		config.setPoolName("etp-migration-postgresql-target");
		config.setJdbcUrl(properties.url());
		config.setUsername(properties.username());
		config.setPassword(properties.password());
		config.setMaximumPoolSize(properties.maxPoolSize() == null || properties.maxPoolSize() < 1
				? 4
				: properties.maxPoolSize());
		config.setDriverClassName("org.postgresql.Driver");
		PostgreSqlTargetProperties.Pool pool = properties.pool();
		config.setConnectionTimeout(pool.connectionTimeout().toMillis());
		config.setValidationTimeout(pool.validationTimeout().toMillis());
		config.setIdleTimeout(pool.idleTimeout().toMillis());
		config.setMaxLifetime(pool.maxLifetime().toMillis());
		config.setKeepaliveTime(pool.keepaliveTime().toMillis());
		HikariPoolSettings.validate(POOL_PREFIX, config);
		return config;
	}

	static final class TargetUrlPresent implements Condition {

		@Override
		public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
			String url = context.getEnvironment().getProperty("etp.migration.target.postgresql.url");
			return url != null && !url.isBlank();
		}
	}
}
