package com.etp.migration.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

/**
 * The target connector also publishes a {@link javax.sql.DataSource}. Spring Boot
 * then skips its pooled auto-configuration, so the control plane must be declared
 * explicitly and marked primary for Flyway and JPA.
 */
@Configuration
public class ControlPlaneDataSourceConfig {

	static final String HIKARI_PREFIX = "spring.datasource.hikari";

	@Bean(destroyMethod = "close")
	@Primary
	public HikariDataSource dataSource(DataSourceProperties properties, Environment environment) {
		HikariDataSource dataSource = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
		Binder.get(environment).bind(HIKARI_PREFIX, Bindable.ofInstance(dataSource));
		HikariPoolSettings.validate(HIKARI_PREFIX, dataSource);
		return dataSource;
	}
}
