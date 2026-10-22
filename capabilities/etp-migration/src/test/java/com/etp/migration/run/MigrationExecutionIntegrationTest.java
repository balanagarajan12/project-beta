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
import org.springframework.jdbc.core.JdbcTemplate;
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
		registry.add("etp.migration.execution.recovery-interval-ms", () -> "3600000");
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
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private MigrationExecutionCoordinator coordinator;
	@Autowired
	private MigrationExecutionClaim executionClaim;
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

		String runId = acceptedRun(definitionId, "{}");
		assertThat(background(runId)).isTrue();

		mockMvc.perform(get("/api/v1/migration-runs/{runId}", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.successRecords").value(2))
				.andExpect(jsonPath("$.processedRecords").value(2))
				.andExpect(jsonPath("$.totalBatches").value(1))
				.andExpect(jsonPath("$.completedBatches").value(1))
				.andExpect(jsonPath("$.failedBatches").value(0));
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

		String runId = acceptedRun(definitionId, "{}");
		mockMvc.perform(get("/api/v1/migration-runs/{runId}", runId).with(jwt()))
				.andExpect(status().isOk())
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

		String runId = acceptedRun(definitionId, "{}");
		mockMvc.perform(get("/api/v1/migration-runs/{runId}", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED"))
				.andExpect(jsonPath("$.failedBatches").value(1));
	}

	@Test
	void executeZeroRecordsCompletesTheRun() throws Exception {
		String definitionId = activeDefinition(10_000, "BULK", true);
		createSource(definitionId, "addr1,city\n");

		String runId = acceptedRun(definitionId, "{}");
		mockMvc.perform(get("/api/v1/migration-runs/{runId}", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.totalBatches").value(0))
				.andExpect(jsonPath("$.failedBatches").value(0));
	}

	@Test
	void executeWithoutASourceFailsTheRunAndRecordsPreparationDetails() throws Exception {
		String definitionId = activeDefinition(10_000, "BULK", true);

		String runId = acceptedRun(definitionId, "{}");
		mockMvc.perform(get("/api/v1/migration-runs/{runId}", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED"));

		mockMvc.perform(get("/api/v1/migrations/{id}/runs", definitionId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].status").value("FAILED"));
		mockMvc.perform(get("/api/v1/migration-runs/{runId}/errors", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].errorCode").value("MIGRATION_PREPARATION_FAILED"))
				.andExpect(jsonPath("$.content[0].errorDetails.phase").value("PREPARE"))
				.andExpect(jsonPath("$.content[0].errorDetails.exceptionType").exists())
				.andExpect(jsonPath("$.content[0].errorDetails.sqlState").doesNotExist())
				.andExpect(jsonPath("$.content[0].retryable").value(false));
	}

	@Test
	void missingStagingTablePersistsCopyDiagnosticsAndLeavesAnUnstartedRunQueued() throws Exception {
		String definitionId = activeDefinition(10_000, "BULK", true, "missing_load");
		MvcResult created = mockMvc.perform(post("/api/v1/migrations/{id}/runs", definitionId)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("QUEUED"))
				.andReturn();
		String queuedRunId = read(created, "$.id");
		createSource(definitionId, """
				addr1,city
				123 Main St,Salem
				""");

		String failedRunId = acceptedRun(definitionId, "{}");
		assertThat(failedRunId).isNotEqualTo(queuedRunId);
		assertThat(background(queuedRunId)).isFalse();
		mockMvc.perform(get("/api/v1/migration-runs/{runId}", failedRunId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED"))
				.andExpect(jsonPath("$.failedBatches").value(1));

		mockMvc.perform(get("/api/v1/migration-runs/{runId}", queuedRunId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("QUEUED"));
		mockMvc.perform(get("/api/v1/migration-runs/{runId}/errors", failedRunId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].errorCode").value("MIGRATION_TARGET_COPY_FAILED"))
				.andExpect(jsonPath("$.content[0].errorMessage").value("Target load failed"))
				.andExpect(jsonPath("$.content[0].errorDetails.sqlState").value("42P01"))
				.andExpect(jsonPath("$.content[0].errorDetails.relation").value("migration_staging.missing_load"))
				.andExpect(jsonPath("$.content[0].errorDetails.phase").value("LOAD"))
				.andExpect(jsonPath("$.content[0].errorDetails.runId").value(failedRunId))
				.andExpect(jsonPath("$.content[0].errorDetails.targetEntity").value("address"))
				.andExpect(jsonPath("$.content[0].errorDetails.retryable").value(true))
				.andExpect(jsonPath("$.content[0].retryable").value(true));

		String stored = transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"select error_details::text from migration_error where migration_run_id = cast(? as uuid)",
				String.class,
				failedRunId));
		assertThat(stored).contains("42P01", "migration_staging.missing_load").doesNotContain("{}");
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
		assertThat(background(runId)).isFalse();
		mockMvc.perform(get("/api/v1/migration-runs/{runId}", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("QUEUED"));
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

		String runId = acceptedRun(definitionId, "{}");
		mockMvc.perform(get("/api/v1/migration-runs/{runId}", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.successRecords").value(1));
	}

	@Test
	void executeKeepsOwnershipAcrossBatchesUntilTheRunFinishes() throws Exception {
		String definitionId = activeDefinition(1, "BULK", true);
		createSource(definitionId, """
				addr1,city
				123 Main St,Salem
				456 Oak Ave,Knoxville
				""");

		String runId = acceptedRun(definitionId, "{}");
		mockMvc.perform(get("/api/v1/migration-runs/{runId}", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.totalBatches").value(2))
				.andExpect(jsonPath("$.completedBatches").value(2))
				.andExpect(jsonPath("$.successRecords").value(2));
	}

	@Test
	void unknownRunReturnsNotFound() throws Exception {
		mockMvc.perform(get("/api/v1/migration-runs/{runId}", UUID.randomUUID()).with(jwt()))
				.andExpect(status().isNotFound());
	}

	@Test
	void repeatedExecuteWithTheSameIdempotencyKeyRunsOnce() throws Exception {
		String definitionId = activeDefinition(10_000, "BULK", true);
		createSource(definitionId, """
				addr1,city
				123 Main St,Salem
				""");
		String body = """
				{"idempotencyKey":"same-execute-%s"}
				""".formatted(System.nanoTime());

		String first = acceptedRun(definitionId, body);
		MvcResult second = mockMvc.perform(post("/api/v1/migrations/{id}/execute", definitionId)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isAccepted())
				.andReturn();
		assertThat(read(second, "$.id")).isEqualTo(first);
		mockMvc.perform(get("/api/v1/migration-runs/{runId}", first).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"));
		assertThat(batchRepository.findByMigrationRun_IdOrderByBatchNumberAsc(UUID.fromString(first))).hasSize(1);
	}

	@Test
	void expiredBackgroundRunIsDiscoveredWithoutExecutingAManualRun() throws Exception {
		String definitionId = activeDefinition(10_000, "BULK", true);
		createSource(definitionId, """
				addr1,city
				123 Main St,Salem
				""");
		String runId = acceptedRun(definitionId, "{}");
		mockMvc.perform(get("/api/v1/migration-runs/{runId}", runId).with(jwt()))
				.andExpect(jsonPath("$.successRecords").value(1));

		jdbcTemplate.update("""
				update migration_run
				set status = 'RUNNING',
				    completed_at = null,
				    execution_worker_id = null,
				    execution_lease_until = null,
				    background_execution = true
				where id = ?
				""", UUID.fromString(runId));
		coordinator.recover();
		ExecutionAwait.untilTerminal(mockMvc, runId);
		mockMvc.perform(get("/api/v1/migration-runs/{runId}", runId).with(jwt()))
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.successRecords").value(1));
		assertThat(batchRepository.findByMigrationRun_IdOrderByBatchNumberAsc(UUID.fromString(runId))).hasSize(1);
	}

	@Test
	void concurrentClaimsCannotBothOwnTheSameRun() throws Exception {
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
				.andReturn();
		UUID runId = UUID.fromString(read(created, "$.id"));
		jdbcTemplate.update("update migration_run set background_execution = true where id = ?", runId);

		java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
		try {
			java.util.concurrent.Future<Boolean> first = pool.submit(() -> {
				start.await();
				return executionClaim.tryClaim(runId);
			});
			java.util.concurrent.Future<Boolean> second = pool.submit(() -> {
				start.await();
				return executionClaim.tryClaim(runId);
			});
			start.countDown();
			assertThat(java.util.List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
		} finally {
			executionClaim.release(runId);
			pool.shutdownNow();
		}
		mockMvc.perform(get("/api/v1/migration-runs/{runId}", runId).with(jwt()))
				.andExpect(jsonPath("$.status").value("RUNNING"));
	}

	private String activeDefinition(int batchSize, String loadMode, boolean activate) throws Exception {
		return activeDefinition(batchSize, loadMode, activate, "address_load");
	}

	private String activeDefinition(int batchSize, String loadMode, boolean activate, String stagingTable) throws Exception {
		String mappingId = createMapping();
		return createDefinition(mappingId, batchSize, loadMode, activate, stagingTable);
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
		return createDefinition(mappingId, batchSize, loadMode, activate, "address_load");
	}

	private String createDefinition(
			String mappingId, int batchSize, String loadMode, boolean activate, String stagingTable) throws Exception {
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
								      "stagingTable": "%s",
								      "mergeStrategy": "NONE"
								    }
								  }
								}
								""".formatted(System.nanoTime(), loadMode, batchSize, mappingId, stagingTable)))
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

	private String acceptedRun(String definitionId, String body) throws Exception {
		MvcResult accepted = mockMvc.perform(post("/api/v1/migrations/{id}/execute", definitionId)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("QUEUED"))
				.andExpect(jsonPath("$.message").value("Migration execution accepted."))
				.andExpect(jsonPath("$.runId").exists())
				.andReturn();
		String runId = read(accepted, "$.id");
		assertThat(accepted.getResponse().getHeader("Location")).isEqualTo("/api/v1/migration-runs/" + runId);
		ExecutionAwait.untilTerminal(mockMvc, runId);
		return runId;
	}

	private boolean background(String runId) {
		return Boolean.TRUE.equals(transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
				"select background_execution from migration_run where id = ?",
				Boolean.class,
				UUID.fromString(runId))));
	}

	private static String read(MvcResult result, String path) throws Exception {
		return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
	}
}
