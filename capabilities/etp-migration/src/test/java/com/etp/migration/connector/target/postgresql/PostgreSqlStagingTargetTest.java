package com.etp.migration.connector.target.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.etp.migration.connector.target.TargetInfrastructureException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PostgreSqlStagingTargetTest {

	private final PostgreSqlTargetProperties properties = new PostgreSqlTargetProperties(
			"jdbc:postgresql://localhost/default", "user", "pw", "public", 2);

	@Test
	void quotesSchemaAndTableSeparatelyAndKeepsCase() {
		PostgreSqlStagingTarget.Resolved staging = PostgreSqlStagingTarget.resolve(Map.of("target", Map.of(
				"schema", "uam",
				"stagingSchema", "Staging_Schema",
				"stagingTable", "Entity_Stage",
				"stagingConnectionRef", "migration-db",
				"connectionRef", "uam-db")), properties);

		String relation = PostgreSqlStagingTarget.quoted(staging);
		assertThat(relation).isEqualTo("\"Staging_Schema\".\"Entity_Stage\"");
		assertThat("COPY " + relation).doesNotContain("\"Staging_Schema.Entity_Stage\"");
	}

	@Test
	void rejectsASchemaQualifiedNameStoredAsOneIdentifier() {
		assertThatThrownBy(() -> PostgreSqlStagingTarget.resolve(Map.of("target", Map.of(
				"stagingSchema", "migration_staging",
				"stagingTable", "migration_staging.uam_entity_stage")), properties))
				.isInstanceOf(TargetInfrastructureException.class)
				.hasMessageContaining("invalid");
	}
}
