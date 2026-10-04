package com.etp.migration.connector.target.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import com.etp.migration.connector.target.LoadableTargetRecord;
import com.etp.migration.connector.target.MigrationTargetContext;
import com.etp.migration.entity.LoadMode;
import com.etp.migration.processing.EntityIdempotencyKey;
import com.etp.migration.processing.TargetRecord;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class PostgreSqlStagedEntityReaderTest {

	@Autowired
	private PostgreSqlTargetConnector stagingConnector;

	@Autowired
	private PostgreSqlStagedEntityReader reader;

	@Autowired
	@Qualifier(PostgreSqlTargetClientConfig.DATASOURCE_BEAN)
	private DataSource targetDataSource;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	@BeforeEach
	void createStaging() throws Exception {
		try (Connection connection = targetDataSource.getConnection()) {
			connection.createStatement().execute("CREATE SCHEMA IF NOT EXISTS migration_staging");
			connection.createStatement().execute("""
					CREATE TABLE IF NOT EXISTS migration_staging.staged_entity_read (
					  migration_run_id UUID NOT NULL,
					  migration_batch_id BIGINT NOT NULL,
					  source_record_number BIGINT NOT NULL,
					  target_payload JSONB NOT NULL,
					  idempotency_key VARCHAR(500),
					  loaded_at TIMESTAMPTZ NOT NULL
					)
					""");
			connection.createStatement().execute("TRUNCATE TABLE migration_staging.staged_entity_read");
		}
	}

	@Test
	void readsOneSourceRecordAsSeparateEntityPayloadsWithNullTargetIdentities() {
		UUID runId = UUID.randomUUID();
		long batchId = 41L;
		List<LoadableTargetRecord> staged = List.of(
				entity(runId, 7L, "AddressLocation", "ADDR001", "address_id", "location",
						Map.of("at_type", "geographicAddress", "name", "Address 001"),
						List.of()),
				entity(runId, 7L, "GeographicAddress", "ADDR001", "address_id", "geographic_address",
						Map.of("street_name", "Sample Street", "state_or_province", "VIC"),
						List.of(
								parent("AddressLocation", "ADDR001"),
								parent("GeographicLocation", "GEO001"))));
		stagingConnector.load(context(runId, batchId), staged);

		List<LoadableTargetRecord> read = reader.read(runId, batchId, configuration());

		assertThat(read).hasSize(2);
		assertThat(read).extracting(LoadableTargetRecord::sourceRecordNumber).containsOnly(7L);
		Map<String, Object> address = fields(read, "GeographicAddress");
		assertThat(address.get("sourceIdentity")).isEqualTo("ADDR001");
		assertThat(address.get("sourceKey")).isEqualTo("address_id");
		assertThat(address.get("targetEntity")).isEqualTo("geographic_address");
		assertThat(address.get("targetKey")).isEqualTo("id");
		assertThat(address.get("targetIdentity")).isNull();
		assertThat(attributes(address)).containsEntry("street_name", "Sample Street").containsEntry("state_or_province", "VIC");
		assertThat(relationships(address)).anySatisfy(link -> {
			assertThat(link.get("parentEntity")).isEqualTo("AddressLocation");
			assertThat(link.get("parentSourceIdentity")).isEqualTo("ADDR001");
			assertThat(link.get("parentTargetIdentity")).isNull();
		});
		assertThat(relationships(address)).anySatisfy(link -> {
			assertThat(link.get("parentEntity")).isEqualTo("GeographicLocation");
			assertThat(link.get("parentSourceIdentity")).isEqualTo("GEO001");
			assertThat(link.get("parentTargetIdentity")).isNull();
		});
		assertThat(read).extracting(LoadableTargetRecord::idempotencyKey)
				.containsExactlyInAnyOrder(
						staged.get(0).idempotencyKey(),
						staged.get(1).idempotencyKey());
	}

	@Test
	void readsFiveHundredStagedEntitiesForOneHundredAddresses() {
		UUID runId = UUID.randomUUID();
		long batchId = 100L;
		List<LoadableTargetRecord> staged = new ArrayList<>();
		for (int index = 1; index <= 100; index++) {
			String address = "ADDR" + index;
			String geo = "GEO" + index;
			long sourceRecord = index;
			staged.add(entity(runId, sourceRecord, "AddressLocation", address, "address_id", "location", Map.of("name", address), List.of()));
			staged.add(entity(runId, sourceRecord, "GeographicPointLocation", geo, "geo_id", "location", Map.of(), List.of()));
			staged.add(entity(runId, sourceRecord, "GeographicLocation", geo, "geo_id", "geographic_location", Map.of(), List.of()));
			staged.add(entity(runId, sourceRecord, "GeographicAddress", address, "address_id", "geographic_address", Map.of(), List.of()));
			staged.add(entity(runId, sourceRecord, "LocationGeometry", geo, "geo_id", "location_geometry", Map.of(), List.of()));
		}
		stagingConnector.load(context(runId, batchId), staged);

		List<LoadableTargetRecord> read = reader.read(runId, batchId, configuration());

		assertThat(read).hasSize(500);
		assertThat(read).allSatisfy(record -> assertThat(record.targetRecord().fields().get("targetIdentity")).isNull());
		assertThat(read.stream().map(record -> record.targetRecord().fields().get("entity")).distinct())
				.containsExactlyInAnyOrder(
						"AddressLocation",
						"GeographicPointLocation",
						"GeographicLocation",
						"GeographicAddress",
						"LocationGeometry");
	}

	private static LoadableTargetRecord entity(
			UUID runId,
			long sourceRecordNumber,
			String entity,
			String sourceIdentity,
			String sourceKey,
			String targetEntity,
			Map<String, Object> attributes,
			List<Map<String, Object>> relationships) {
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("entity", entity);
		fields.put("targetEntity", targetEntity);
		fields.put("sourceIdentity", sourceIdentity);
		fields.put("targetIdentity", null);
		fields.put("sourceKey", sourceKey);
		fields.put("targetKey", "id");
		fields.put("sourceRecordNumber", sourceRecordNumber);
		fields.put("attributes", attributes);
		fields.put("relationships", relationships);
		return new LoadableTargetRecord(
				sourceRecordNumber,
				new TargetRecord(fields),
				EntityIdempotencyKey.of(runId, entity, sourceIdentity));
	}

	private static Map<String, Object> parent(String entity, String sourceIdentity) {
		Map<String, Object> link = new LinkedHashMap<>();
		link.put("parentEntity", entity);
		link.put("parentSourceIdentity", sourceIdentity);
		link.put("parentTargetIdentity", null);
		return link;
	}

	private static MigrationTargetContext context(UUID runId, long batchId) {
		return new MigrationTargetContext(
				runId,
				batchId,
				1,
				PostgreSqlTargetConnector.TARGET_TYPE,
				"location",
				LoadMode.BULK,
				configuration());
	}

	private static Map<String, Object> configuration() {
		return Map.of("target", Map.of(
				"schema", "migration_staging",
				"stagingTable", "staged_entity_read",
				"mergeStrategy", "NONE"));
	}

	private static Map<String, Object> fields(List<LoadableTargetRecord> records, String entity) {
		return records.stream()
				.map(record -> record.targetRecord().fields())
				.filter(payload -> entity.equals(payload.get("entity")))
				.findFirst()
				.orElseThrow();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> attributes(Map<String, Object> payload) {
		return (Map<String, Object>) payload.get("attributes");
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> relationships(Map<String, Object> payload) {
		return (List<Map<String, Object>>) payload.get("relationships");
	}
}
