package com.etp.migration.connector.target.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.etp.migration.connector.target.LoadableTargetRecord;
import com.etp.migration.connector.target.MigrationTargetContext;
import com.etp.migration.connector.target.TargetInfrastructureException;
import com.etp.migration.connector.target.TargetLoadModeNotSupportedException;
import com.etp.migration.connector.target.TargetLoadResult;
import com.etp.migration.entity.LoadMode;
import com.etp.migration.processing.EntityIdempotencyKey;
import com.etp.migration.processing.FlatIdempotencyKey;
import com.etp.migration.processing.MigrationErrorCodes;
import com.etp.migration.processing.TargetRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
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

@SpringBootTest
class PostgreSqlTargetConnectorTest {

	@Autowired
	@Qualifier(PostgreSqlTargetClientConfig.DATASOURCE_BEAN)
	private DataSource targetDataSource;

	@Autowired
	private PostgreSqlTargetConnector connector;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@MockitoBean
	private JwtDecoder jwtDecoder;

	@BeforeEach
	void createStaging() throws Exception {
		try (Connection connection = targetDataSource.getConnection()) {
			connection.createStatement().execute("CREATE SCHEMA IF NOT EXISTS migration_staging");
			connection.createStatement().execute("""
					CREATE TABLE IF NOT EXISTS migration_staging.address_load (
					  migration_run_id UUID NOT NULL,
					  migration_batch_id BIGINT NOT NULL,
					  source_record_number BIGINT NOT NULL,
					  target_payload JSONB NOT NULL,
					  idempotency_key VARCHAR(500),
					  loaded_at TIMESTAMPTZ NOT NULL,
					  UNIQUE (migration_run_id, migration_batch_id, source_record_number)
					)
					""");
			connection.createStatement().execute("""
					CREATE TABLE IF NOT EXISTS migration_staging.address_load_constrained (
					  migration_run_id UUID NOT NULL,
					  migration_batch_id BIGINT NOT NULL,
					  source_record_number BIGINT NOT NULL CHECK (source_record_number >= 0),
					  target_payload JSONB NOT NULL,
					  idempotency_key VARCHAR(500),
					  loaded_at TIMESTAMPTZ NOT NULL,
					  UNIQUE (migration_run_id, migration_batch_id, source_record_number)
					)
					""");
			connection.createStatement().execute("""
					CREATE TABLE IF NOT EXISTS migration_staging.entity_identity_load (
					  migration_run_id UUID NOT NULL,
					  migration_batch_id BIGINT NOT NULL,
					  source_record_number BIGINT NOT NULL,
					  target_payload JSONB NOT NULL,
					  idempotency_key VARCHAR(500),
					  loaded_at TIMESTAMPTZ NOT NULL,
					  UNIQUE (migration_run_id, idempotency_key)
					)
					""");
		}
	}

	@Test
	void bulkModeIsSupportedAndSingleIsRejected() {
		assertThat(connector.targetType()).isEqualTo("POSTGRESQL");
		assertThat(connector.supportsLoadMode(LoadMode.BULK)).isTrue();
		assertThatThrownBy(() -> connector.load(context(UUID.randomUUID(), 1L, LoadMode.SINGLE, "address_load"), List.of(record(UUID.randomUUID(), 1L, 1L, Map.of("city", "Salem")))))
				.isInstanceOf(TargetLoadModeNotSupportedException.class);
	}

	@Test
	void copyInsertsOneRecordAndPreservesEnvelope() throws Exception {
		UUID runId = UUID.randomUUID();
		TargetLoadResult result = connector.load(context(runId, 11L, LoadMode.BULK, "address_load"), List.of(record(runId, 11L, 10001L, Map.of("streetName", "123 Main"))));
		assertThat(result.submittedCount()).isEqualTo(1);
		assertThat(result.successCount()).isEqualTo(1);
		assertThat(result.failedCount()).isZero();
		assertThat(result.targetReferences()).isEmpty();
		try (Connection connection = targetDataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT source_record_number, target_payload, idempotency_key
						FROM migration_staging.address_load
						WHERE migration_run_id = ? AND migration_batch_id = ?
						""")) {
			statement.setObject(1, runId);
			statement.setLong(2, 11L);
			try (ResultSet rs = statement.executeQuery()) {
				assertThat(rs.next()).isTrue();
				assertThat(rs.getLong("source_record_number")).isEqualTo(10001L);
				assertThat(rs.getString("idempotency_key")).isEqualTo(FlatIdempotencyKey.of(runId, 11L, 10001L));
				JsonNode payload = objectMapper.readTree(rs.getString("target_payload"));
				assertThat(payload.get("streetName").asText()).isEqualTo("123 Main");
				assertThat(rs.next()).isFalse();
			}
		}
	}

	@Test
	void copyInsertsTenThousandRecords() throws Exception {
		UUID runId = UUID.randomUUID();
		List<LoadableTargetRecord> records = new ArrayList<>(10_000);
		for (int i = 1; i <= 10_000; i++) {
			records.add(record(runId, 12L, i, Map.of("n", i)));
		}
		TargetLoadResult result = connector.load(context(runId, 12L, LoadMode.BULK, "address_load"), records);
		assertThat(result.successCount()).isEqualTo(10_000);
		assertThat(countRows(runId, 12L, "address_load")).isEqualTo(10_000);
	}

	@Test
	void copyEscapesJsonSpecialCharactersAndNulls() throws Exception {
		UUID runId = UUID.randomUUID();
		Map<String, Object> fields = new HashMap<>();
		fields.put("streetName", "He said \"hello\", then left");
		fields.put("city", "Salem, TN");
		fields.put("notes", "line1\nline2");
		fields.put("unicode", "Café 東京");
		fields.put("empty", "");
		fields.put("missing", null);
		connector.load(context(runId, 13L, LoadMode.BULK, "address_load"), List.of(record(runId, 13L, 7L, fields)));
		JsonNode payload = payload(runId, 13L, 7L);
		assertThat(payload.get("streetName").asText()).isEqualTo("He said \"hello\", then left");
		assertThat(payload.get("city").asText()).isEqualTo("Salem, TN");
		assertThat(payload.get("notes").asText()).isEqualTo("line1\nline2");
		assertThat(payload.get("unicode").asText()).isEqualTo("Café 東京");
		assertThat(payload.get("empty").asText()).isEmpty();
		assertThat(payload.get("missing").isNull()).isTrue();
	}

	@Test
	void retryReplacesStagingRowsInsteadOfDuplicating() throws Exception {
		UUID runId = UUID.randomUUID();
		MigrationTargetContext context = context(runId, 14L, LoadMode.BULK, "address_load");
		connector.load(context, List.of(record(runId, 14L, 1L, Map.of("city", "first"))));
		connector.load(context, List.of(record(runId, 14L, 1L, Map.of("city", "second"))));
		assertThat(countRows(runId, 14L, "address_load")).isEqualTo(1);
		assertThat(payload(runId, 14L, 1L).get("city").asText()).isEqualTo("second");
	}

	@Test
	void copyFailureRollsBackPartialRows() throws Exception {
		UUID runId = UUID.randomUUID();
		List<LoadableTargetRecord> records = List.of(
				record(runId, 15L, 1L, Map.of("city", "ok")),
				record(runId, 15L, -1L, Map.of("city", "bad")));
		assertThatThrownBy(() -> connector.load(context(runId, 15L, LoadMode.BULK, "address_load_constrained"), records))
				.isInstanceOf(TargetInfrastructureException.class)
				.extracting(ex -> ((TargetInfrastructureException) ex).errorCode())
				.isEqualTo(MigrationErrorCodes.TARGET_COPY_FAILED);
		assertThat(countRows(runId, 15L, "address_load_constrained")).isZero();
	}

	@Test
	@Tag("performance")
	void copyOneHundredThousandRecords() throws Exception {
		UUID runId = UUID.randomUUID();
		List<LoadableTargetRecord> records = new ArrayList<>(100_000);
		for (int i = 1; i <= 100_000; i++) {
			records.add(record(runId, 16L, i, Map.of("n", i)));
		}
		TargetLoadResult result = connector.load(context(runId, 16L, LoadMode.BULK, "address_load"), records);
		assertThat(result.successCount()).isEqualTo(100_000);
		assertThat(countRows(runId, 16L, "address_load")).isEqualTo(100_000);
	}

	private static MigrationTargetContext context(UUID runId, long batchId, LoadMode loadMode, String table) {
		return new MigrationTargetContext(
				runId,
				batchId,
				1,
				"POSTGRESQL",
				"address",
				loadMode,
				Map.of("target", Map.of("schema", "migration_staging", "stagingTable", table, "mergeStrategy", "NONE")));
	}

	private static LoadableTargetRecord record(UUID runId, long batchId, long number, Map<String, Object> fields) {
		return new LoadableTargetRecord(number, new TargetRecord(fields), FlatIdempotencyKey.of(runId, batchId, number));
	}

	@Test
	void entityIdentitiesSharingASourceRecordStayDistinctAcrossBatchRetry() throws Exception {
		UUID runId = UUID.randomUUID();
		String parentKey = EntityIdempotencyKey.of(runId, "EntityA", "A1");
		String childKey = EntityIdempotencyKey.of(runId, "EntityB", "A1");
		assertThat(parentKey).isNotEqualTo(childKey);
		assertThat(parentKey).doesNotContain("A1");
		LoadableTargetRecord parent = new LoadableTargetRecord(105L, new TargetRecord(Map.of("entity", "EntityA")), parentKey);
		LoadableTargetRecord child = new LoadableTargetRecord(105L, new TargetRecord(Map.of("entity", "EntityB")), childKey);
		connector.load(context(runId, 1L, LoadMode.BULK, "entity_identity_load"), List.of(parent, child));
		assertThat(countByKey(runId, parentKey, "entity_identity_load")).isEqualTo(1);
		assertThat(countByKey(runId, childKey, "entity_identity_load")).isEqualTo(1);

		connector.load(context(runId, 7L, LoadMode.BULK, "entity_identity_load"), List.of(parent));
		assertThat(countByKey(runId, parentKey, "entity_identity_load")).isEqualTo(1);
		assertThat(countByKey(runId, childKey, "entity_identity_load")).isEqualTo(1);
		assertThat(countRows(runId, "entity_identity_load")).isEqualTo(2);

		connector.load(context(runId, 1L, LoadMode.BULK, "entity_identity_load"), List.of(parent, child));
		assertThat(countRows(runId, "entity_identity_load")).isEqualTo(2);
	}

	private int countRows(UUID runId, long batchId, String table) throws Exception {
		try (Connection connection = targetDataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT COUNT(*) FROM migration_staging." + table + " WHERE migration_run_id = ? AND migration_batch_id = ?")) {
			statement.setObject(1, runId);
			statement.setLong(2, batchId);
			try (ResultSet rs = statement.executeQuery()) {
				rs.next();
				return rs.getInt(1);
			}
		}
	}

	private int countRows(UUID runId, String table) throws Exception {
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

	private int countByKey(UUID runId, String key, String table) throws Exception {
		try (Connection connection = targetDataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT COUNT(*) FROM migration_staging." + table
								+ " WHERE migration_run_id = ? AND idempotency_key = ?")) {
			statement.setObject(1, runId);
			statement.setString(2, key);
			try (ResultSet rs = statement.executeQuery()) {
				rs.next();
				return rs.getInt(1);
			}
		}
	}

	private JsonNode payload(UUID runId, long batchId, long sourceRecordNumber) throws Exception {
		try (Connection connection = targetDataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT target_payload FROM migration_staging.address_load
						WHERE migration_run_id = ? AND migration_batch_id = ? AND source_record_number = ?
						""")) {
			statement.setObject(1, runId);
			statement.setLong(2, batchId);
			statement.setLong(3, sourceRecordNumber);
			try (ResultSet rs = statement.executeQuery()) {
				assertThat(rs.next()).isTrue();
				return objectMapper.readTree(rs.getString("target_payload"));
			}
		}
	}
}
