package com.etp.migration.promotion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.etp.migration.connector.target.postgresql.PostgreSqlTargetClientConfig;
import com.etp.migration.entity.MigrationConfigStatus;
import com.etp.migration.entity.MigrationDefinition;
import com.etp.migration.entity.MigrationPromotion;
import com.etp.migration.entity.PromotionStatus;
import com.etp.migration.processing.MigrationErrorCodes;
import com.etp.migration.repository.MigrationDefinitionRepository;
import com.etp.migration.repository.MigrationPromotionRepository;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
class PromotionPreparationIntegrationTest {

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
	private MigrationPromotionRepository promotionRepository;
	@Autowired
	private TransactionTemplate transactionTemplate;
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
		}
	}

	@Test
	void getReturnsReadyPromotionWithoutMutation() throws Exception {
		String runId = processCsv("""
				addr1,city
				123 Main St,Salem
				456 Oak Ave,Knoxville
				""", true);
		reconcile(runId);
		MvcResult prepared = mockMvc.perform(post("/api/v1/migration-runs/{runId}/promotion/prepare", runId).with(jwt()))
				.andExpect(status().isOk())
				.andReturn();
		String promotionId = com.jayway.jsonpath.JsonPath.read(prepared.getResponse().getContentAsString(), "$.promotionId");
		Instant updatedAt = transactionTemplate.execute(tx ->
				promotionRepository.findById(UUID.fromString(promotionId)).orElseThrow().getUpdatedAt());

		mockMvc.perform(get("/api/v1/migration-runs/{runId}/promotion", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.promotionId").value(promotionId))
				.andExpect(jsonPath("$.runId").value(runId))
				.andExpect(jsonPath("$.status").value("READY"))
				.andExpect(jsonPath("$.eligibleBatches").value(1))
				.andExpect(jsonPath("$.promotedBatches").value(0))
				.andExpect(jsonPath("$.batches[0].promotionStatus").value("ELIGIBLE"))
				.andExpect(jsonPath("$.batches[0].eligibleRecordCount").value(2))
				.andExpect(jsonPath("$.batches[0].promotedRecordCount").value(0));

		Instant afterGet = transactionTemplate.execute(tx ->
				promotionRepository.findById(UUID.fromString(promotionId)).orElseThrow().getUpdatedAt());
		assertThat(afterGet).isEqualTo(updatedAt);
		assertThat(promotionRepository.findByMigrationRun_IdOrderByCreatedAtDesc(UUID.fromString(runId))).hasSize(1);
	}

	@Test
	void getReturnsNotFoundWhenPromotionDoesNotExist() throws Exception {
		String runId = processCsv("""
				addr1,city
				123 Main St,Salem
				""", true);
		mockMvc.perform(get("/api/v1/migration-runs/{runId}/promotion", runId).with(jwt()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.message").value("Promotion not found for migration run " + runId));
		assertThat(promotionRepository.findByMigrationRun_IdOrderByCreatedAtDesc(UUID.fromString(runId))).isEmpty();
	}

	@Test
	void getReturnsNotFoundWhenRunDoesNotExist() throws Exception {
		UUID missing = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
		mockMvc.perform(get("/api/v1/migration-runs/{runId}/promotion", missing).with(jwt()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.message").value("Migration run not found: " + missing));
	}

	@Test
	void preparesEligibleBatchesFromStagingCountsAfterReconciliation() throws Exception {
		String runId = processCsv("""
				addr1,city
				123 Main St,Salem
				456 Oak Ave,Knoxville
				""", true);
		reconcile(runId);
		MvcResult prepared = mockMvc.perform(post("/api/v1/migration-runs/{runId}/promotion/prepare", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.runId").value(runId))
				.andExpect(jsonPath("$.status").value("READY"))
				.andExpect(jsonPath("$.eligibleBatches").value(1))
				.andExpect(jsonPath("$.eligibleRecords").value(2))
				.andExpect(jsonPath("$.skippedRecords").value(0))
				.andExpect(jsonPath("$.batches[0].promotionStatus").value("ELIGIBLE"))
				.andExpect(jsonPath("$.batches[0].eligibleRecordCount").value(2))
				.andReturn();
		String promotionId = com.jayway.jsonpath.JsonPath.read(prepared.getResponse().getContentAsString(), "$.promotionId");
		assertThat(countStaging(UUID.fromString(runId))).isEqualTo(2);
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/promotion/prepare", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.promotionId").value(promotionId))
				.andExpect(jsonPath("$.status").value("READY"));
	}

	@Test
	void completedWithErrorsUsesStagingRowsOnly() throws Exception {
		String runId = processCsv("""
				addr1,city
				123 Main St,Salem
				,
				""", true);
		reconcile(runId);
		int staged = countStaging(UUID.fromString(runId));
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/promotion/prepare", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("READY"))
				.andExpect(jsonPath("$.eligibleRecords").value(staged))
				.andExpect(jsonPath("$.batches[0].promotionStatus").value("ELIGIBLE"));
	}

	@Test
	void rejectsWhenReconciliationIsMissing() throws Exception {
		String runId = processCsv("""
				addr1,city
				123 Main St,Salem
				""", true);
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/promotion/prepare", runId).with(jwt()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value(MigrationErrorCodes.PROMOTION_RECONCILIATION_REQUIRED));
	}

	@Test
	void rejectsDisabledPromotion() throws Exception {
		String runId = processCsv("""
				addr1,city
				123 Main St,Salem
				""", false);
		reconcile(runId);
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/promotion/prepare", runId).with(jwt()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(MigrationErrorCodes.PROMOTION_DISABLED));
	}

	@Test
	void rejectsPromotingInProgress() throws Exception {
		String runId = processCsv("""
				addr1,city
				123 Main St,Salem
				""", true);
		reconcile(runId);
		MvcResult prepared = mockMvc.perform(post("/api/v1/migration-runs/{runId}/promotion/prepare", runId).with(jwt()))
				.andExpect(status().isOk())
				.andReturn();
		String promotionId = com.jayway.jsonpath.JsonPath.read(prepared.getResponse().getContentAsString(), "$.promotionId");
		transactionTemplate.executeWithoutResult(tx -> {
			MigrationPromotion promotion = promotionRepository.findById(UUID.fromString(promotionId)).orElseThrow();
			promotion.setStatus(PromotionStatus.PROMOTING);
		});
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/promotion/prepare", runId).with(jwt()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value(MigrationErrorCodes.PROMOTION_IN_PROGRESS));
	}

	private String processCsv(String csv, boolean promotionEnabled) throws Exception {
		String objectKey = "samples/promo-" + System.nanoTime() + ".csv";
		writeCsv(objectKey, csv);
		String mappingId = createMapping();
		String definitionId = createActiveDefinition(mappingId, promotionEnabled);
		createSource(definitionId, objectKey);
		String runId = createRun(definitionId);
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/prepare", runId).with(jwt()))
				.andExpect(status().isOk());
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/process-next-batch", runId).with(jwt()))
				.andExpect(status().isOk());
		return runId;
	}

	private void reconcile(String runId) throws Exception {
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/reconcile", runId).with(jwt()))
				.andExpect(status().isOk());
	}

	private String createMapping() throws Exception {
		MvcResult created = mockMvc.perform(post("/api/v1/mappings")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "phase13b-%s",
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

	private String createActiveDefinition(String mappingId, boolean promotionEnabled) throws Exception {
		String promotion = promotionEnabled
				? """
						,
						    "promotion": {
						      "enabled": true,
						      "schema": "uam",
						      "table": "address",
						      "strategy": "INSERT",
						      "columns": {
						        "streetName": "address_line_1"
						      }
						    }
						"""
				: """
						,
						    "promotion": {
						      "enabled": false
						    }
						""";
		MvcResult created = mockMvc.perform(post("/api/v1/migrations")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "phase13b-def-%s",
								  "version": "1.0.0",
								  "sourceType": "FILE",
								  "targetType": "POSTGRESQL",
								  "targetEntity": "address",
								  "loadMode": "BULK",
								  "batchSize": 10000,
								  "mappingId": "%s",
								  "configuration": {
								    "target": {
								      "schema": "migration_staging",
								      "stagingTable": "address_load",
								      "mergeStrategy": "NONE"
								    }%s
								  }
								}
								""".formatted(System.nanoTime(), mappingId, promotion)))
				.andExpect(status().isCreated())
				.andReturn();
		String id = readId(created);
		transactionTemplate.executeWithoutResult(tx -> {
			MigrationDefinition definition = migrationDefinitionRepository.findById(UUID.fromString(id)).orElseThrow();
			definition.setStatus(MigrationConfigStatus.ACTIVE);
		});
		return id;
	}

	private void createSource(String definitionId, String objectKey) throws Exception {
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

	private void writeCsv(String objectKey, String content) throws Exception {
		Path path = storageRoot.resolve(objectKey);
		Files.createDirectories(path.getParent());
		Files.writeString(path, content);
	}

	private int countStaging(UUID runId) throws Exception {
		try (Connection connection = targetDataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT COUNT(*) FROM migration_staging.address_load WHERE migration_run_id = ?")) {
			statement.setObject(1, runId);
			try (ResultSet rs = statement.executeQuery()) {
				rs.next();
				return rs.getInt(1);
			}
		}
	}

	private String readId(MvcResult result) throws Exception {
		return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id");
	}
}
