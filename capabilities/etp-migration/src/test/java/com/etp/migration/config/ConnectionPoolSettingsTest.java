package com.etp.migration.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.etp.migration.connector.target.postgresql.PostgreSqlTargetClientConfig;
import com.etp.migration.connector.target.postgresql.PostgreSqlTargetProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class ConnectionPoolSettingsTest {

	private static final String UNUSED_URL = "jdbc:postgresql://127.0.0.1:1/unused";

	private final ApplicationContextRunner controlPlane = new ApplicationContextRunner()
			.withUserConfiguration(ControlPlaneOnly.class)
			.withPropertyValues(
					"spring.datasource.url=" + UNUSED_URL,
					"spring.datasource.username=user",
					"spring.datasource.password=unused");

	private final ApplicationContextRunner targetProperties = new ApplicationContextRunner()
			.withUserConfiguration(TargetPropertiesOnly.class);

	@Test
	void documentedDefaultsAreConsistent() {
		HikariConfig config = config(c -> {
			c.setConnectionTimeout(10_000);
			c.setValidationTimeout(5_000);
			c.setIdleTimeout(600_000);
			c.setMaxLifetime(1_800_000);
			c.setKeepaliveTime(120_000);
		});

		assertThatCode(() -> HikariPoolSettings.validate("p", config)).doesNotThrowAnyException();
	}

	@Test
	void inconsistentTimeoutsAreRejectedWithThePropertyName() {
		assertRejected(c -> c.setConnectionTimeout(0), "p.connection-timeout");
		assertRejected(c -> c.setConnectionTimeout(300_001), "p.connection-timeout");
		assertRejected(c -> c.setValidationTimeout(10_000), "p.validation-timeout");
		assertRejected(c -> c.setMaxLifetime(10_000), "p.max-lifetime");
		assertRejected(c -> c.setKeepaliveTime(1_800_000), "p.keepalive-time");
		assertRejected(c -> c.setKeepaliveTime(5_000), "p.keepalive-time");
		assertRejected(c -> c.setIdleTimeout(1_800_000), "p.idle-timeout");
		assertRejected(c -> c.setIdleTimeout(5_000), "p.idle-timeout");
	}

	@Test
	void disabledLifetimeKeepaliveAndIdleTimeoutAreAllowed() {
		HikariConfig config = config(c -> {
			c.setMaxLifetime(0);
			c.setKeepaliveTime(0);
			c.setIdleTimeout(0);
		});

		assertThatCode(() -> HikariPoolSettings.validate("p", config)).doesNotThrowAnyException();
	}

	@Test
	void controlPlanePoolBindsOverridesFromSpringDatasourceHikari() {
		controlPlane.withPropertyValues(
						"spring.datasource.hikari.connection-timeout=7000",
						"spring.datasource.hikari.validation-timeout=3000",
						"spring.datasource.hikari.idle-timeout=300000",
						"spring.datasource.hikari.max-lifetime=900000",
						"spring.datasource.hikari.keepalive-time=60000",
						"spring.datasource.hikari.maximum-pool-size=6")
				.run(context -> {
					HikariDataSource dataSource = context.getBean(HikariDataSource.class);
					assertThat(dataSource.getConnectionTimeout()).isEqualTo(7_000);
					assertThat(dataSource.getValidationTimeout()).isEqualTo(3_000);
					assertThat(dataSource.getIdleTimeout()).isEqualTo(300_000);
					assertThat(dataSource.getMaxLifetime()).isEqualTo(900_000);
					assertThat(dataSource.getKeepaliveTime()).isEqualTo(60_000);
					assertThat(dataSource.getMaximumPoolSize()).isEqualTo(6);
				});
	}

	@Test
	void inconsistentControlPlaneSettingsStopStartup() {
		controlPlane.withPropertyValues(
						"spring.datasource.hikari.max-lifetime=600000",
						"spring.datasource.hikari.idle-timeout=600000")
				.run(context -> assertThat(context).hasFailed().getFailure()
						.rootCause()
						.hasMessageContaining("spring.datasource.hikari.idle-timeout"));
	}

	@Test
	void targetPoolDefaultsApplyWithoutConfiguration() {
		targetProperties.run(context -> {
			HikariConfig config = targetHikariConfig(context.getBean(PostgreSqlTargetProperties.class));
			assertThat(config.getConnectionTimeout()).isEqualTo(10_000);
			assertThat(config.getValidationTimeout()).isEqualTo(5_000);
			assertThat(config.getIdleTimeout()).isEqualTo(600_000);
			assertThat(config.getMaxLifetime()).isEqualTo(1_800_000);
			assertThat(config.getKeepaliveTime()).isEqualTo(120_000);
			assertThat(config.getMaximumPoolSize()).isEqualTo(4);
		});
	}

	@Test
	void targetPoolBindsDurationOverrides() {
		targetProperties.withPropertyValues(
						"etp.migration.target.postgresql.pool.connection-timeout=3s",
						"etp.migration.target.postgresql.pool.validation-timeout=1s",
						"etp.migration.target.postgresql.pool.idle-timeout=2m",
						"etp.migration.target.postgresql.pool.max-lifetime=15m",
						"etp.migration.target.postgresql.pool.keepalive-time=1m")
				.run(context -> {
					PostgreSqlTargetProperties properties = context.getBean(PostgreSqlTargetProperties.class);
					assertThat(properties.pool().connectionTimeout()).isEqualTo(Duration.ofSeconds(3));
					HikariConfig config = targetHikariConfig(properties);
					assertThat(config.getConnectionTimeout()).isEqualTo(3_000);
					assertThat(config.getMaxLifetime()).isEqualTo(900_000);
				});
	}

	@Test
	void inconsistentTargetPoolSettingsStopStartupBeforeConnecting() {
		new ApplicationContextRunner()
				.withUserConfiguration(PostgreSqlTargetClientConfig.class)
				.withPropertyValues(
						"etp.migration.target.postgresql.url=" + UNUSED_URL,
						"etp.migration.target.postgresql.pool.keepalive-time=40m")
				.run(context -> assertThat(context).hasFailed().getFailure()
						.rootCause()
						.hasMessageContaining("etp.migration.target.postgresql.pool.keepalive-time"));
	}

	private static HikariConfig targetHikariConfig(PostgreSqlTargetProperties properties) {
		return PostgreSqlTargetClientConfig.hikariConfig(properties);
	}

	private static void assertRejected(Consumer<HikariConfig> change, String property) {
		HikariConfig config = config(c -> {
			c.setConnectionTimeout(10_000);
			c.setValidationTimeout(5_000);
			c.setIdleTimeout(600_000);
			c.setMaxLifetime(1_800_000);
			c.setKeepaliveTime(120_000);
			change.accept(c);
		});
		assertThatThrownBy(() -> HikariPoolSettings.validate("p", config))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageStartingWith(property);
	}

	private static HikariConfig config(Consumer<HikariConfig> settings) {
		HikariConfig config = new HikariConfig();
		settings.accept(config);
		return config;
	}

	@Configuration
	@EnableConfigurationProperties(DataSourceProperties.class)
	@Import(ControlPlaneDataSourceConfig.class)
	static class ControlPlaneOnly {
	}

	@Configuration
	@EnableConfigurationProperties(PostgreSqlTargetProperties.class)
	static class TargetPropertiesOnly {
	}
}
