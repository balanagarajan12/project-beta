package com.etp.migration.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.etp.migration.connector.target.postgresql.PostgreSqlTargetClientConfig;
import com.etp.migration.entity.MigrationBatchStatus;
import com.etp.migration.entity.MigrationConfigStatus;
import com.etp.migration.entity.MigrationDefinition;
import com.etp.migration.repository.MigrationBatchRepository;
import com.etp.migration.repository.MigrationDefinitionRepository;
import com.etp.migration.repository.MigrationPromotionRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
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
class MigrationExecutionIntegrationTest {

	@TempDir
	static Path storageRoot;

	@DynamicPropertySource
	static void storageProperties(DynamicPropertyRegistry registry) {
		registry.add("etp.migration.storage.root", () -> storageRoot.toAbsolutePath().toString());
	}

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private MigrationDefinitionRepository definitionRepository;
	@Autowired
	private MigrationBatchRepository batchRepository;
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
		try (Connection connection = targetDataSource.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute("CREATE SCHEMA IF NOT EXISTS migration_staging");
			statement.execute("""
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
	void executeProcessesAllBatchesToCompleted() throws Exception {
		long promotions = promotionRepository.count();
		String definitionId = activeDefinition(10_000, "BULK", true);
		createSource(definitionId, """
				addr1,city
				123 Main St,Salem
				456 Oak Ave,Knoxville
				""");

		MvcResult executed = mockMvc.perform(post("/api/v1/migrations/{id}/execute", definitionId)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.successRecords").value(2))
				.andExpect(jsonPath("$.processedRecords").value(2))
				.andExpect(jsonPath("$.totalBatches").value(1))
				.andExpect(jsonPath("$.completedBatches").value(1))
				.andExpect(jsonPath("$.failedBatches").value(0))
				.andReturn();
		String runId = read(executed, "$.id");
		assertThat(executed.getResponse().getHeader("Location")).isEqualTo("/api/v1/migration-runs/" + runId);

		mockMvc.perform(get("/api/v1/migration-runs/{runId}", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"));
		assertThat(batchRepository.findByMigrationRun_IdOrderByBatchNumberAsc(UUID.fromString(runId)))
				.hasSize(1)
				.allMatch(batch -> batch.getStatus() == MigrationBatchStatus.COMPLETED);
		assertThat(promotionRepository.count()).isEqualTo(promotions);
		assertThat(definitionRepository.findById(UUID.fromString(definitionId)).orElseThrow().getStatus())
				.isEqualTo(MigrationConfigStatus.ACTIVE);
	}

	@Test
	void executeWithValidationFailureCompletesWithErrors() throws Exception {
		String definitionId = activeDefinition(10_000, "BULK", true);
		createSource(definitionId, """
				addr1,city
				,Salem
				456 Oak Ave,Knoxville
				""");

		mockMvc.perform(post("/api/v1/migrations/{id}/execute", definitionId)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("COMPLETED_WITH_ERRORS"))
				.andExpect(jsonPath("$.successRecords").value(1))
				.andExpect(jsonPath("$.validationFailures").value(1))
				.andExpect(jsonPath("$.failedBatches").value(1));
	}

	@Test
	void executeHardBatchFailureReturnsFailed() throws Exception {
		String definitionId = activeDefinition(10_000, "SINGLE", true);
		createSource(definitionId, """
				addr1,city
				123 Main St,Salem
				""");

		mockMvc.perform(post("/api/v1/migrations/{id}/execute", definitionId)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("FAILED"))
				.andExpect(jsonPath("$.failedBatches").value(1));
	}

	@Test
	void executeZeroRecordsLeavesRunQueued() throws Exception {
		String definitionId = activeDefinition(10_000, "BULK", true);
		createSource(definitionId, "addr1,city\n");

		mockMvc.perform(post("/api/v1/migrations/{id}/execute", definitionId)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("QUEUED"))
				.andExpect(jsonPath("$.totalBatches").value(0));
	}

	@Test
	void executeDraftDefinitionReturnsConflict() throws Exception {
		String mappingId = createMapping();
		String definitionId = createDefinition(mappingId, 10_000, "BULK", false);

		mockMvc.perform(post("/api/v1/migrations/{id}/execute", definitionId)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isConflict());
	}

	@Test
	void stepwiseRunApiStillWorks() throws Exception {
		String definitionId = activeDefinition(10_000, "BULK", true);
		createSource(definitionId, """
				addr1,city
				123 Main St,Salem
				""");

		MvcResult created = mockMvc.perform(post("/api/v1/migrations/{id}/runs", definitionId)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("QUEUED"))
				.andReturn();
		String runId = read(created, "$.id");
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/prepare", runId).with(jwt()))
				.andExpect(status().isOk());
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/process-next-batch", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"));
	}

	@Test
	void executeFileSystemCsvSourceCompletes() throws Exception {
		String definitionId = activeDefinition(10_000, "BULK", true);
		createSource(definitionId, """
				addr1,city
				123 Main St,Salem
				""", "FILE_SYSTEM");

		mockMvc.perform(post("/api/v1/migrations/{id}/execute", definitionId)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.successRecords").value(1));
	}

	private String activeDefinition(int batchSize, String loadMode, boolean activate) throws Exception {
		String mappingId = createMapping();
		return createDefinition(mappingId, batchSize, loadMode, activate);
	}

	private String createMapping() throws Exception {
		MvcResult created = mockMvc.perform(post("/api/v1/mappings")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "phase14b-%s",
								  "version": "1.0.0",
								  "sourceType": "FILE",
								  "targetType": "POSTGRESQL"
								}
								""".formatted(System.nanoTime())))
				.andExpect(status().isCreated())
				.andReturn();
		String mappingId = read(created, "$.id");
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

	private String createDefinition(String mappingId, int batchSize, String loadMode, boolean activate) throws Exception {
		MvcResult created = mockMvc.perform(post("/api/v1/migrations")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "phase14b-def-%s",
								  "version": "1.0.0",
								  "sourceType": "FILE",
								  "targetType": "POSTGRESQL",
								  "targetEntity": "address",
								  "loadMode": "%s",
								  "batchSize": %s,
								  "mappingId": "%s",
								  "configuration": {
								    "target": {
								      "schema": "migration_staging",
								      "stagingTable": "address_load",
								      "mergeStrategy": "NONE"
								    }
								  }
								}
								""".formatted(System.nanoTime(), loadMode, batchSize, mappingId)))
				.andExpect(status().isCreated())
				.andReturn();
		String id = read(created, "$.id");
		if (activate) {
			transactionTemplate.executeWithoutResult(tx -> {
				MigrationDefinition definition = definitionRepository.findById(UUID.fromString(id)).orElseThrow();
				definition.setStatus(MigrationConfigStatus.ACTIVE);
			});
		}
		return id;
	}

	private void createSource(String definitionId, String csv) throws Exception {
		createSource(definitionId, csv, "FILE");
	}

	private void createSource(String definitionId, String csv, String sourceType) throws Exception {
		String objectKey = "samples/phase14b-" + System.nanoTime() + ".csv";
		Path path = storageRoot.resolve(objectKey);
		Files.createDirectories(path.getParent());
		Files.writeString(path, csv);
		mockMvc.perform(post("/api/v1/migrations/{id}/sources", definitionId)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "csv-source",
								  "sourceType": "%s",
								  "configuration": {
								    "provider": "FILE_SYSTEM",
								    "objectKey": "%s",
								    "fileFormat": "CSV",
								    "hasHeader": true
								  }
								}
								""".formatted(sourceType, objectKey)))
				.andExpect(status().isCreated());
	}

	private static String read(MvcResult result, String path) throws Exception {
		return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
	}
}
