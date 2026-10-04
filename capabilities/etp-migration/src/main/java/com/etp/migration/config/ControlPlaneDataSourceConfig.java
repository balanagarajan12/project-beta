package com.etp.migration.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * The target connector also publishes a {@link javax.sql.DataSource}. Spring Boot
 * then skips its pooled auto-configuration, so the control plane must be declared
 * explicitly and marked primary for Flyway and JPA.
 */
@Configuration
public class ControlPlaneDataSourceConfig {

	@Bean(destroyMethod = "close")
	@Primary
	@ConfigurationProperties(prefix = "spring.datasource.hikari")
	public HikariDataSource dataSource(DataSourceProperties properties) {
		return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
	}
}
