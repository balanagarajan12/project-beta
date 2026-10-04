package com.etp.migration.performance;

import static org.assertj.core.api.Assertions.assertThat;

import com.etp.migration.connector.target.LoadableTargetRecord;
import com.etp.migration.connector.target.MigrationTargetContext;
import com.etp.migration.connector.target.postgresql.PostgreSqlTargetClientConfig;
import com.etp.migration.connector.target.postgresql.PostgreSqlTargetConnector;
import com.etp.migration.entity.LoadMode;
import com.etp.migration.processing.EntityIdempotencyKey;
import com.etp.migration.processing.FlatIdempotencyKey;
import com.etp.migration.processing.TargetRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Local staging COPY measurement. PostgreSQL staging only. Not a production-capacity claim.
 * Reconciliation is not included in the copy time.
 */
@Tag("performance")
@SpringBootTest
class EntityStagingBenchmarkTest {

	private static final int ROWS = 10_000;

	@Autowired
	@Qualifier(PostgreSqlTargetClientConfig.DATASOURCE_BEAN)
	private DataSource targetDataSource;

	@Autowired
	private PostgreSqlTargetConnector connector;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	@BeforeEach
	void createStaging() throws Exception {
		try (Connection connection = targetDataSource.getConnection()) {
			connection.createStatement().execute("CREATE SCHEMA IF NOT EXISTS migration_staging");
			connection.createStatement().execute("""
					CREATE TABLE IF NOT EXISTS migration_staging.entity_identity_benchmark (
					  migration_run_id UUID NOT NULL,
					  migration_batch_id BIGINT NOT NULL,
					  source_record_number BIGINT NOT NULL,
					  target_payload JSONB NOT NULL,
					  idempotency_key VARCHAR(500),
					  loaded_at TIMESTAMPTZ NOT NULL,
					  UNIQUE (migration_run_id, idempotency_key)
					)
					""");
			connection.createStatement().execute("""
					CREATE TABLE IF NOT EXISTS migration_staging.flat_benchmark (
					  migration_run_id UUID NOT NULL,
					  migration_batch_id BIGINT NOT NULL,
					  source_record_number BIGINT NOT NULL,
					  target_payload JSONB NOT NULL,
					  idempotency_key VARCHAR(500),
					  loaded_at TIMESTAMPTZ NOT NULL,
					  UNIQUE (migration_run_id, migration_batch_id, source_record_number)
					)
					""");
		}
	}

	@Test
	void measuresStagingCopySeparatelyFromReconciliation() throws Exception {
		Map<String, Object> flat = copyFlat();
		Map<String, Object> entity = copyEntities();
		ObjectMapper json = new ObjectMapper().findAndRegisterModules();
		System.out.println("PHASE10_STAGING_BENCHMARK " + json.writeValueAsString(List.of(flat, entity)));
		assertThat(flat.get("status")).isEqualTo("MEASURED");
		assertThat(entity.get("status")).isEqualTo("MEASURED");
	}

	private Map<String, Object> copyFlat() throws Exception {
		UUID runId = UUID.randomUUID();
		List<LoadableTargetRecord> rows = new ArrayList<>(ROWS);
		for (int index = 1; index <= ROWS; index++) {
			rows.add(new LoadableTargetRecord(
					index,
					new TargetRecord(Map.of("label", "row-" + index)),
					FlatIdempotencyKey.of(runId, 1L, index)));
		}
		long elapsed = copy(runId, 1L, "flat_benchmark", rows);
		assertThat(count(runId, "flat_benchmark")).isEqualTo(ROWS);
		return result("FLAT_STAGING_COPY", runId, elapsed, ROWS, "migration_staging.flat_benchmark");
	}

	private Map<String, Object> copyEntities() throws Exception {
		UUID runId = UUID.randomUUID();
		List<LoadableTargetRecord> rows = new ArrayList<>(ROWS);
		for (int index = 1; index <= ROWS; index++) {
			rows.add(new LoadableTargetRecord(
					105L,
					new TargetRecord(Map.of("entity", "EntityC", "sourceIdentity", "C" + index)),
					EntityIdempotencyKey.of(runId, "EntityC", "C" + index)));
		}
		long elapsed = copy(runId, 1L, "entity_identity_benchmark", rows);
		assertThat(count(runId, "entity_identity_benchmark")).isEqualTo(ROWS);
		long retryStarted = System.nanoTime();
		copy(runId, 7L, "entity_identity_benchmark", rows);
		long retryMs = Math.max(1L, (System.nanoTime() - retryStarted) / 1_000_000L);
		assertThat(count(runId, "entity_identity_benchmark")).isEqualTo(ROWS);
		Map<String, Object> result = result(
				"ENTITY_STAGING_COPY",
				runId,
				elapsed,
				ROWS,
				"migration_staging.entity_identity_benchmark");
		result.put("retryMs", retryMs);
		result.put("rowsAfterRetry", ROWS);
		return result;
	}

	private long copy(UUID runId, long batchId, String table, List<LoadableTargetRecord> rows) {
		long started = System.nanoTime();
		connector.load(new MigrationTargetContext(
				runId,
				batchId,
				1,
				"POSTGRESQL",
				"entity_c",
				LoadMode.BULK,
				Map.of("target", Map.of("schema", "migration_staging", "stagingTable", table, "mergeStrategy", "NONE"))), rows);
		return Math.max(1L, (System.nanoTime() - started) / 1_000_000L);
	}

	private int count(UUID runId, String table) throws Exception {
		try (Connection connection = targetDataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT COUNT(*) FROM migration_staging." + table + " WHERE migration_run_id = ?")) {
			statement.setObject(1, runId);
			try (ResultSet rs = statement.executeQuery()) {
				rs.next();
				return rs.getInt(1);
			}
		}
	}

	private static Map<String, Object> result(String scenario, UUID runId, long elapsedMs, int rows, String table) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("scenario", scenario);
		map.put("measurement", "local benchmark measurement");
		map.put("target", "postgresql-staging");
		map.put("table", table);
		map.put("runId", runId.toString());
		map.put("records", rows);
		map.put("elapsedMs", elapsedMs);
		map.put("recordsPerSecond", BenchmarkMetrics.recordsPerSecond(rows, elapsedMs));
		map.put("reconciliationMs", 0);
		map.put("status", "MEASURED");
		map.put("note", "Copy time only. Reconciliation is not included.");
		return map;
	}
}
