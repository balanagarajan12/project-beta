package com.etp.migration.connector.target.postgresql;

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

	@Bean(name = DATASOURCE_BEAN, destroyMethod = "close")
	@Conditional(TargetUrlPresent.class)
	@Qualifier(DATASOURCE_BEAN)
	public HikariDataSource postgresqlTargetDataSource(PostgreSqlTargetProperties properties) {
		HikariConfig config = new HikariConfig();
		config.setPoolName("etp-migration-postgresql-target");
		config.setJdbcUrl(properties.url());
		config.setUsername(properties.username());
		config.setPassword(properties.password());
		config.setMaximumPoolSize(properties.maxPoolSize() == null || properties.maxPoolSize() < 1
				? 4
				: properties.maxPoolSize());
		config.setDriverClassName("org.postgresql.Driver");
		return new HikariDataSource(config);
	}

	static final class TargetUrlPresent implements Condition {

		@Override
		public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
			String url = context.getEnvironment().getProperty("etp.migration.target.postgresql.url");
			return url != null && !url.isBlank();
		}
	}
}
