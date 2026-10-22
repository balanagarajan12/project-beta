package com.etp.migration.connector.target.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.etp.migration.connector.target.TargetInfrastructureException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PostgreSqlConnectionRegistryTest {

	@Test
	void missingConnectionReferenceNamesTheReferenceAndOmitsSecrets() {
		PostgreSqlConnectionProperties profiles = new PostgreSqlConnectionProperties();
		PostgreSqlConnectionProperties.Profile profile = new PostgreSqlConnectionProperties.Profile();
		profile.setUrl("jdbc:postgresql://localhost/unused");
		profile.setUsername("migrator");
		profile.setPassword("secret-value");
		profiles.getConnections().put("customer-db", profile);
		PostgreSqlConnectionRegistry registry = new PostgreSqlConnectionRegistry(
				profiles,
				new PostgreSqlTargetProperties("jdbc:postgresql://localhost/default", "user", "secret-value", "public", 2),
				null);

		assertThatThrownBy(() -> registry.target(Map.of("target", Map.of("connectionRef", "service-db"))))
				.isInstanceOf(TargetInfrastructureException.class)
				.hasMessageContaining("service-db")
				.hasMessageContaining("is not configured")
				.hasMessageNotContaining("secret-value");
	}

	@Test
	void stagingAndTargetUseDistinctProfiles() {
		PostgreSqlConnectionRegistry registry = registryWith(
				profile("jdbc:postgresql://localhost:55446/etp_migration", "staging-secret"),
				profile("jdbc:postgresql://localhost:55445/etp_migration", "business-secret"));
		Map<String, Object> configuration = Map.of("target", Map.of(
				"stagingConnectionRef", "migration-db",
				"connectionRef", "uam-db"));

		assertThat(registry.jdbcUrl(configuration, true))
				.isEqualTo("jdbc:postgresql://localhost:55446/etp_migration");
		assertThat(registry.jdbcUrl(configuration, false))
				.isEqualTo("jdbc:postgresql://localhost:55445/etp_migration");
	}

	@Test
	void missingStagingProfileDoesNotFallBackToTheBusinessConnection() {
		PostgreSqlConnectionProperties profiles = new PostgreSqlConnectionProperties();
		profiles.getConnections().put("uam-db", profile("jdbc:postgresql://localhost:55445/etp_migration", "business-secret"));
		PostgreSqlConnectionRegistry registry = new PostgreSqlConnectionRegistry(
				profiles,
				new PostgreSqlTargetProperties("jdbc:postgresql://localhost/default", "user", "secret-value", "public", 2),
				new DriverManagerDataSource());

		assertThatThrownBy(() -> registry.staging(Map.of("target", Map.of(
						"stagingConnectionRef", "migration-db",
						"connectionRef", "uam-db"))))
				.isInstanceOf(TargetInfrastructureException.class)
				.hasMessageContaining("migration-db")
				.hasMessageContaining("is not configured")
				.hasMessageNotContaining("business-secret")
				.hasMessageNotContaining("55445");
	}

	@Test
	void blankReferenceUsesTheDefaultDatasource() {
		PostgreSqlConnectionProperties profiles = new PostgreSqlConnectionProperties();
		DriverManagerDataSource fallback = new DriverManagerDataSource();
		PostgreSqlConnectionRegistry registry = new PostgreSqlConnectionRegistry(
				profiles,
				new PostgreSqlTargetProperties("jdbc:postgresql://localhost/default", "user", "pw", "public", 2),
				fallback);

		assertThat(registry.staging(Map.of())).isSameAs(fallback);
		assertThat(registry.target(Map.of("target", Map.of("schema", "customer")))).isSameAs(fallback);
	}

	private static PostgreSqlConnectionRegistry registryWith(
			PostgreSqlConnectionProperties.Profile staging,
			PostgreSqlConnectionProperties.Profile business) {
		PostgreSqlConnectionProperties profiles = new PostgreSqlConnectionProperties();
		profiles.getConnections().put("migration-db", staging);
		profiles.getConnections().put("uam-db", business);
		return new PostgreSqlConnectionRegistry(
				profiles,
				new PostgreSqlTargetProperties("jdbc:postgresql://localhost/default", "user", "secret-value", "public", 2),
				null);
	}

	private static PostgreSqlConnectionProperties.Profile profile(String url, String password) {
		PostgreSqlConnectionProperties.Profile profile = new PostgreSqlConnectionProperties.Profile();
		profile.setUrl(url);
		profile.setUsername("migrator");
		profile.setPassword(password);
		return profile;
	}
}
