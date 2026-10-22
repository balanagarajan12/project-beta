package com.etp.migration.connector.target.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class StagingBatchLookupIndexTest {

	@Test
	void flywayRecordsTheMigrationWithoutCreatingAnIndexWhenStagingIsAbsent() throws Exception {
		try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))) {
			postgres.start();
			Flyway.configure()
					.dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
					.locations("classpath:db/migration")
					.load()
					.migrate();
			try (Connection connection = open(postgres)) {
				assertThat(versionApplied(connection, "9")).isTrue();
				assertThat(indexValid(connection)).isNull();
			}
		}
	}

	@Test
	void indexIsCreatedOutsideATransactionAndASecondCallLeavesItValid() throws Exception {
		try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))) {
			postgres.start();
			try (Connection connection = open(postgres)) {
				connection.setAutoCommit(false);
				assertThatThrownBy(() -> PostgreSqlStagingIndexes.createBatchLookupIfPresent(connection))
						.isInstanceOf(SQLException.class)
						.hasMessageContaining("transaction");
				connection.rollback();
				connection.setAutoCommit(true);
				try (Statement statement = connection.createStatement()) {
					statement.execute("CREATE SCHEMA migration_staging");
					statement.execute("""
							CREATE TABLE migration_staging.uam_entity_stage (
							    migration_run_id UUID NOT NULL,
							    migration_batch_id BIGINT NOT NULL,
							    source_record_number BIGINT NOT NULL,
							    target_payload JSONB NOT NULL,
							    idempotency_key VARCHAR(500),
							    loaded_at TIMESTAMPTZ NOT NULL
							)
							""");
				}
				PostgreSqlStagingIndexes.createBatchLookupIfPresent(connection);
				assertThat(indexValid(connection)).isTrue();
				PostgreSqlStagingIndexes.createBatchLookupIfPresent(connection);
				assertThat(indexValid(connection)).isTrue();
			}
		}
	}

	private static Connection open(PostgreSQLContainer<?> postgres) throws SQLException {
		return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
	}

	private static boolean versionApplied(Connection connection, String version) throws SQLException {
		try (Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery(
						"SELECT success FROM flyway_schema_history WHERE version = '" + version + "'")) {
			return resultSet.next() && resultSet.getBoolean(1);
		}
	}

	private static Boolean indexValid(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery("""
						SELECT i.indisvalid
						FROM pg_index i
						JOIN pg_class c ON c.oid = i.indexrelid
						WHERE c.relname = 'idx_uam_entity_stage_run_batch'
						""")) {
			if (!resultSet.next()) {
				return null;
			}
			return resultSet.getBoolean(1);
		}
	}
}
