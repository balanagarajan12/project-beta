package com.etp.migration.config;

import javax.sql.DataSource;
import org.springframework.boot.jdbc.health.DataSourceHealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Health of the control-plane database only, published as {@code controlPlaneDb} so the readiness group can
 * include it without depending on whether the optional business-target pool exists.
 */
@Configuration
public class ControlPlaneHealthConfig {

	@Bean
	public DataSourceHealthIndicator controlPlaneDbHealthIndicator(DataSource dataSource) {
		return new DataSourceHealthIndicator(dataSource);
	}
}
