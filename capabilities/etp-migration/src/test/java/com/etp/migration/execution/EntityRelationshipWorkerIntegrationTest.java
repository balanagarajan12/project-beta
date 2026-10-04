package com.etp.migration.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.etp.migration.connector.target.postgresql.PostgreSqlTargetClientConfig;
import com.etp.migration.entity.MigrationBatch;
import com.etp.migration.entity.MigrationConfigStatus;
import com.etp.migration.entity.MigrationDefinition;
import com.etp.migration.identity.TargetIdentityLookup;
import com.etp.migration.repository.MigrationBatchRepository;
import com.etp.migration.repository.MigrationDefinitionRepository;
import com.etp.migration.repository.MigrationRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
class EntityRelationshipWorkerIntegrationTest {

	@TempDir
	static Path storageRoot;

	@DynamicPropertySource
	static void storageProperties(DynamicPropertyRegistry registry) {
		registry.add("etp.migration.storage.root", () -> storageRoot.toAbsolutePath().toString());
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private MigrationDefinitionRepository migrationDefinitionRepository;

	@Autowired
	private MigrationBatchRepository migrationBatchRepository;

	@Autowired
	private MigrationRecordRepository migrationRecordRepository;

	@Autowired
	private TransactionTemplate transactionTemplate;

	@Autowired
	@Qualifier(PostgreSqlTargetClientConfig.DATASOURCE_BEAN)
	private DataSource targetDataSource;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@BeforeEach
	void createStaging() throws Exception {
		try (Connection connection = targetDataSource.getConnection()) {
			connection.createStatement().execute("CREATE SCHEMA IF NOT EXISTS migration_staging");
			connection.createStatement().execute("""
					CREATE TABLE IF NOT EXISTS migration_staging.entity_load (
					  migration_run_id UUID NOT NULL,
					  migration_batch_id BIGINT NOT NULL,
					  source_record_number BIGINT NOT NULL,
					  target_payload JSONB NOT NULL,
					  idempotency_key VARCHAR(500),
					  loaded_at TIMESTAMPTZ NOT NULL
					)
					""");
			connection.createStatement().execute("""
					CREATE UNIQUE INDEX IF NOT EXISTS uk_entity_load_run_identity
					ON migration_staging.entity_load (migration_run_id, idempotency_key)
					""");
		}
	}

	@Test
	void flatCsvMigrationKeepsTheRecordPipeline() throws Exception {
		String objectKey = "samples/flat-" + System.nanoTime() + ".csv";
		write(objectKey, """
				addr1,city
				123 Main St,Salem
				456 Oak Ave,Knoxville
				""");
		String mappingId = createFlatMapping();
		String definitionId = createDefinition(mappingId, 10_000);
		createFlatSource(definitionId, objectKey);
		String runId = createRun(definitionId);
		prepare(runId);

		assertThat(locatorType(runId)).isEqualTo("CSV_ROW_RANGE");
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/process-next-batch", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.totalRecords").value(2))
				.andExpect(jsonPath("$.successRecords").value(2))
				.andExpect(jsonPath("$.failedRecords").value(0));
		mockMvc.perform(get("/api/v1/migration-runs/{runId}", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.successRecords").value(2));
		assertThat(migrationRecordRepository.countByMigrationRun_Id(UUID.fromString(runId))).isZero();
		assertThat(countStaging(UUID.fromString(runId))).isEqualTo(2);
	}

	@Test
	void entityRelationshipCsvReachesStagingWithParentTargetIdentities() throws Exception {
		String objectKey = "samples/entity-" + System.nanoTime() + ".csv";
		write(objectKey, """
				location_id,location_name,address_id,address_name,address_location_id,site_id,site_name,site_address_id
				L001,Main,,,,,,
				,,A001,One,L001,,,
				,,A002,Two,L001,,,
				,,,,,S001,S1,A001
				,,,,,S002,S2,A001
				,,,,,S003,S3,A002
				""");
		String mappingId = createEntityMapping();
		String definitionId = createDefinition(mappingId, 2);
		createFlatSource(definitionId, objectKey);
		String runId = createRun(definitionId);
		MvcResult prepared = prepare(runId);
		int batches = ((Number) com.jayway.jsonpath.JsonPath.read(
				prepared.getResponse().getContentAsString(), "$.batchCount")).intValue();

		assertThat(batches).isGreaterThan(1);
		assertThat(locatorType(runId)).isEqualTo("ENTITY_RELATIONSHIP_BATCH");
		int success = 0;
		for (int i = 0; i < batches; i++) {
			MvcResult processed = mockMvc.perform(post("/api/v1/migration-runs/{runId}/process-next-batch", runId).with(jwt()))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.status").value("COMPLETED"))
					.andReturn();
			success += ((Number) com.jayway.jsonpath.JsonPath.read(
					processed.getResponse().getContentAsString(), "$.successRecords")).intValue();
		}
		mockMvc.perform(get("/api/v1/migration-runs/{runId}", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"));

		assertThat(success).isEqualTo(6);
		assertThat(migrationRecordRepository.countByMigrationRun_Id(UUID.fromString(runId))).isZero();
		List<Map<String, Object>> payloads = payloads(UUID.fromString(runId));
		assertThat(payloads).hasSize(6);
		Map<String, Object> address = payloads.stream()
				.filter(payload -> "Address".equals(payload.get("entity")) && "A001".equals(payload.get("sourceIdentity")))
				.findFirst()
				.orElseThrow();
		assertThat(address.get("targetIdentity")).isEqualTo("TA001");
		assertThat(address.get("targetIdentity")).isNotEqualTo("A001");
		assertThat(parentTarget(address)).isEqualTo("TL001");
		Map<String, Object> site = payloads.stream()
				.filter(payload -> "Site".equals(payload.get("entity")) && "S003".equals(payload.get("sourceIdentity")))
				.findFirst()
				.orElseThrow();
		assertThat(site.get("targetIdentity")).isEqualTo("TS003");
		assertThat(parentTarget(site)).isEqualTo("TA002");
	}

	@TestConfiguration
	static class IdentityLookupConfiguration {
		@Bean
		TargetIdentityLookup targetIdentityLookup() {
			Map<String, Map<String, String>> data = new LinkedHashMap<>();
			data.put("Location", Map.of("L001", "TL001"));
			data.put("Address", Map.of("A001", "TA001", "A002", "TA002"));
			data.put("Site", Map.of("S001", "TS001", "S002", "TS002", "S003", "TS003"));
			return (entityName, sourceIdentities) -> {
				Map<String, String> known = data.getOrDefault(entityName, Map.of());
				Map<String, String> resolved = new LinkedHashMap<>();
				for (String sourceIdentity : sourceIdentities) {
					if (known.containsKey(sourceIdentity)) {
						resolved.put(sourceIdentity, known.get(sourceIdentity));
					}
				}
				return resolved;
			};
		}
	}

	private String locatorType(String runId) {
		return transactionTemplate.execute(status -> {
			List<MigrationBatch> batches = migrationBatchRepository
					.findByMigrationRun_IdOrderByBatchNumberAsc(UUID.fromString(runId));
			return String.valueOf(batches.get(0).getSourceLocator().get("type"));
		});
	}

	private String createFlatMapping() throws Exception {
		MvcResult created = mockMvc.perform(post("/api/v1/mappings")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "flat-%s",
								  "version": "1.0.0",
								  "sourceType": "FILE",
								  "targetType": "POSTGRESQL"
								}
								""".formatted(System.nanoTime())))
				.andExpect(status().isCreated())
				.andReturn();
		String mappingId = readId(created);
		mockMvc.perform(post("/api/v1/mappings/{id}/rules", mappingId)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "sequenceNo": 1,
								  "sourceField": "addr1",
								  "targetField": "streetName",
								  "transformationType": "TRIM",
								  "mandatory": true,
								  "enabled": true
								}
								"""))
				.andExpect(status().isCreated());
		return mappingId;
	}

	private String createEntityMapping() throws Exception {
		MvcResult created = mockMvc.perform(post("/api/v1/mappings")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "entity-%s",
								  "version": "1.0.0",
								  "sourceType": "FILE",
								  "targetType": "POSTGRESQL",
								  "configuration": {
								    "modelType": "ENTITY_RELATIONSHIP",
								    "rootEntity": "Location",
								    "entities": [
								      {"name": "Location", "targetEntity": "geographic_location", "sourceKey": "location_id", "targetKey": "id"},
								      {"name": "Address", "targetEntity": "geographic_address", "sourceKey": "address_id", "targetKey": "id"},
								      {"name": "Site", "targetEntity": "geographic_site", "sourceKey": "site_id", "targetKey": "id"}
								    ],
								    "relationships": [
								      {
								        "parent": "Location",
								        "child": "Address",
								        "cardinality": "ONE_TO_MANY",
								        "keys": [{"parentField": "location_id", "childField": "address_location_id"}]
								      },
								      {
								        "parent": "Address",
								        "child": "Site",
								        "cardinality": "ONE_TO_MANY",
								        "keys": [{"parentField": "address_id", "childField": "site_address_id"}]
								      }
								    ]
								  }
								}
								""".formatted(System.nanoTime())))
				.andExpect(status().isCreated())
				.andReturn();
		String mappingId = readId(created);
		createRule(mappingId, 1, "Location", "location_name");
		createRule(mappingId, 2, "Address", "address_name");
		createRule(mappingId, 3, "Site", "site_name");
		return mappingId;
	}

	private void createRule(String mappingId, int sequence, String entity, String sourceField) throws Exception {
		mockMvc.perform(post("/api/v1/mappings/{id}/rules", mappingId)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "sequenceNo": %d,
								  "entity": "%s",
								  "sourceField": "%s",
								  "targetField": "name",
								  "transformationType": "TRIM",
								  "mandatory": true,
								  "enabled": true
								}
								""".formatted(sequence, entity, sourceField)))
				.andExpect(status().isCreated());
	}

	private String createDefinition(String mappingId, int batchSize) throws Exception {
		MvcResult created = mockMvc.perform(post("/api/v1/migrations")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "entity-run-%s",
								  "version": "1.0.0",
								  "sourceType": "FILE",
								  "targetType": "POSTGRESQL",
								  "targetEntity": "staged_entity",
								  "loadMode": "BULK",
								  "batchSize": %d,
								  "mappingId": "%s",
								  "configuration": {
								    "target": {
								      "schema": "migration_staging",
								      "stagingTable": "entity_load",
								      "mergeStrategy": "NONE"
								    }
								  }
								}
								""".formatted(System.nanoTime(), batchSize, mappingId)))
				.andExpect(status().isCreated())
				.andReturn();
		String id = readId(created);
		transactionTemplate.executeWithoutResult(tx -> {
			MigrationDefinition definition = migrationDefinitionRepository.findById(UUID.fromString(id)).orElseThrow();
			definition.setStatus(MigrationConfigStatus.ACTIVE);
		});
		return id;
	}

	private void createFlatSource(String definitionId, String objectKey) throws Exception {
		mockMvc.perform(post("/api/v1/migrations/{id}/sources", definitionId)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "csv-source",
								  "sourceType": "FILE",
								  "configuration": {
								    "provider": "FILE_SYSTEM",
								    "objectKey": "%s",
								    "fileFormat": "CSV",
								    "hasHeader": true
								  }
								}
								""".formatted(objectKey)))
				.andExpect(status().isCreated());
	}

	private String createRun(String definitionId) throws Exception {
		MvcResult created = mockMvc.perform(post("/api/v1/migrations/{id}/runs", definitionId)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isCreated())
				.andReturn();
		return readId(created);
	}

	private MvcResult prepare(String runId) throws Exception {
		return mockMvc.perform(post("/api/v1/migration-runs/{runId}/prepare", runId).with(jwt()))
				.andExpect(status().isOk())
				.andReturn();
	}

	private void write(String objectKey, String content) throws Exception {
		Path path = storageRoot.resolve(objectKey);
		Files.createDirectories(path.getParent());
		Files.writeString(path, content);
	}

	private int countStaging(UUID runId) throws Exception {
		try (Connection connection = targetDataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT COUNT(*) FROM migration_staging.entity_load WHERE migration_run_id = ?")) {
			statement.setObject(1, runId);
			try (ResultSet resultSet = statement.executeQuery()) {
				resultSet.next();
				return resultSet.getInt(1);
			}
		}
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> payloads(UUID runId) throws Exception {
		List<Map<String, Object>> payloads = new ArrayList<>();
		try (Connection connection = targetDataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT target_payload::text FROM migration_staging.entity_load WHERE migration_run_id = ?")) {
			statement.setObject(1, runId);
			try (ResultSet resultSet = statement.executeQuery()) {
				while (resultSet.next()) {
					payloads.add(objectMapper.readValue(resultSet.getString(1), Map.class));
				}
			}
		}
		return payloads;
	}

	@SuppressWarnings("unchecked")
	private static String parentTarget(Map<String, Object> payload) {
		List<Map<String, Object>> relationships = (List<Map<String, Object>>) payload.get("relationships");
		return String.valueOf(relationships.get(0).get("parentTargetIdentity"));
	}

	private static String readId(MvcResult result) throws Exception {
		return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id");
	}
}
