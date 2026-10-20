package com.etp.migration.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.etp.migration.connector.target.postgresql.PostgreSqlTargetClientConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.jdbc.health.DataSourceHealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.Shutdown;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.core.env.Environment;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Loads the production {@code application.properties} explicitly because the test resource of the same name
 * shadows it on the classpath.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(locations = "file:src/main/resources/application.properties")
class OperationalReadinessIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private HealthEndpointGroups groups;

	@Autowired
	private DataSource controlPlane;

	@Autowired
	private DataSourceHealthIndicator controlPlaneDbHealthIndicator;

	@Autowired
	@Qualifier(PostgreSqlTargetClientConfig.DATASOURCE_BEAN)
	private HikariDataSource target;

	@Autowired
	private Environment environment;

	@Autowired
	private ServerProperties serverProperties;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	@Test
	void readinessContainsTheControlPlaneDatabaseAndLivenessDoesNot() {
		assertThat(groups.get("readiness").isMember("readinessState")).isTrue();
		assertThat(groups.get("readiness").isMember("controlPlaneDb")).isTrue();
		assertThat(groups.get("readiness").isMember("db")).isFalse();
		assertThat(groups.get("liveness").isMember("livenessState")).isTrue();
		assertThat(groups.get("liveness").isMember("controlPlaneDb")).isFalse();
		assertThat(groups.get("liveness").isMember("db")).isFalse();
	}

	@Test
	void gracefulShutdownAndGracePeriodAreExplicit() {
		assertThat(environment.getProperty("server.shutdown")).isEqualTo("graceful");
		assertThat(environment.getProperty("spring.lifecycle.timeout-per-shutdown-phase", Duration.class))
				.isEqualTo(Duration.ofSeconds(30));
		assertThat(serverProperties.getShutdown()).isEqualTo(Shutdown.GRACEFUL);
	}

	@Test
	void probesArePublicAndExposeOnlyTheStatus() throws Exception {
		for (String path : new String[] {"/actuator/health", "/actuator/health/readiness", "/actuator/health/liveness"}) {
			String body = mockMvc.perform(get(path))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.status").value("UP"))
					.andExpect(jsonPath("$.components").doesNotExist())
					.andExpect(jsonPath("$.details").doesNotExist())
					.andReturn()
					.getResponse()
					.getContentAsString();
			assertThat(body.toLowerCase()).doesNotContain("jdbc", "postgres", "password", "username");
		}
	}

	@Test
	void controlPlaneIndicatorChecksThePrimaryDataSource() {
		assertThat(controlPlaneDbHealthIndicator.health(false).getStatus()).isEqualTo(Status.UP);
		assertThat(controlPlane).isInstanceOf(HikariDataSource.class);
		assertThat(((HikariDataSource) controlPlane).getPoolName()).isEqualTo("etp-migration-control-plane");
	}

	@Test
	void productionPoolDefaultsApplyToEachIntendedPool() {
		HikariDataSource primary = (HikariDataSource) controlPlane;
		assertThat(primary.getPoolName()).isEqualTo("etp-migration-control-plane");
		assertThat(primary.getConnectionTimeout()).isEqualTo(10_000);
		assertThat(primary.getValidationTimeout()).isEqualTo(5_000);
		assertThat(primary.getIdleTimeout()).isEqualTo(600_000);
		assertThat(primary.getMaxLifetime()).isEqualTo(1_800_000);
		assertThat(primary.getKeepaliveTime()).isEqualTo(120_000);
		assertThat(primary.getMaximumPoolSize()).isEqualTo(10);
		assertThat(primary.getMinimumIdle()).isEqualTo(10);

		assertThat(target.getPoolName()).isEqualTo("etp-migration-postgresql-target");
		assertThat(target.getConnectionTimeout()).isEqualTo(10_000);
		assertThat(target.getValidationTimeout()).isEqualTo(5_000);
		assertThat(target.getIdleTimeout()).isEqualTo(600_000);
		assertThat(target.getMaxLifetime()).isEqualTo(1_800_000);
		assertThat(target.getKeepaliveTime()).isEqualTo(120_000);
		assertThat(target.getMaximumPoolSize()).isEqualTo(4);
	}

	@Test
	void indicatorReportsDownWhenTheDatabaseIsUnreachable() {
		try (HikariDataSource unreachable = new HikariDataSource()) {
			unreachable.setJdbcUrl("jdbc:postgresql://127.0.0.1:1/unreachable");
			unreachable.setConnectionTimeout(Duration.ofMillis(500).toMillis());
			unreachable.setInitializationFailTimeout(-1);
			Health health = new DataSourceHealthIndicator(unreachable).health(false);

			assertThat(health.getStatus()).isEqualTo(Status.DOWN);
		}
	}
}
