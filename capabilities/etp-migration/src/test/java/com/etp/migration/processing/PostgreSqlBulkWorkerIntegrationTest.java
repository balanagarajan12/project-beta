package com.etp.migration.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.etp.migration.connector.target.postgresql.PostgreSqlTargetClientConfig;
import com.etp.migration.run.PhaseAwait;
import com.etp.migration.entity.MigrationConfigStatus;
import com.etp.migration.entity.MigrationDefinition;
import com.etp.migration.repository.MigrationDefinitionRepository;
import com.etp.migration.repository.MigrationReconciliationRepository;
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
class PostgreSqlBulkWorkerIntegrationTest {

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
	private TransactionTemplate transactionTemplate;

	@Autowired
	private MigrationReconciliationRepository reconciliationRepository;

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
	void postgresqlBulkCopyCompletesWithoutPersistingSuccessfulRecords() throws Exception {
		String objectKey = "samples/pg-copy-" + System.nanoTime() + ".csv";
		writeCsv(objectKey, """
				addr1,city
				123 Main St,Salem
				456 Oak Ave,Knoxville
				""");
		String mappingId = createMapping();
		String definitionId = createActiveDefinition(mappingId);
		createSource(definitionId, objectKey);
		String runId = createRun(definitionId);
		prepare(runId);
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/process-next-batch", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.successRecords").value(2));
		assertThat(countStaging(UUID.fromString(runId))).isEqualTo(2);
	}

	@Test
	void reconcilesMatchingPostgresqlStagingCounts() throws Exception {
		String runId = processTwoRowRun();
		MvcResult first = PhaseAwait.reconciled(mockMvc, runId)
				.andExpect(jsonPath("$.runId").value(runId))
				.andExpect(jsonPath("$.reconciliation.count.status").value("PASSED"))
				.andExpect(jsonPath("$.reconciliation.count.details.verificationScope").value("STAGING"))
				.andExpect(jsonPath("$.reconciliation.count.details.businessTargetVerified").value(false))
				.andExpect(jsonPath("$.reconciliation.count.details.targetCount").value(2))
				.andExpect(jsonPath("$.reconciliation.target.status").value("PASSED"))
				.andExpect(jsonPath("$.reconciliation.target.details.mergeStrategy").value("NONE"))
				.andExpect(jsonPath("$.reconciliation.checksum.status").value("PASSED_WITH_WARNINGS"))
				.andExpect(jsonPath("$.reconciliation.checksum.details.comparisonPerformed").value(false))
				.andExpect(jsonPath("$.reconciliation.checksum.details.sourceChecksum").isNotEmpty())
				.andExpect(jsonPath("$.reconciliation.full.status").value("PASSED_WITH_WARNINGS"))
				.andReturn();
		String fullId = com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(), "$.reconciliation.full.id");
		PhaseAwait.reconciled(mockMvc, runId)
				.andExpect(jsonPath("$.reconciliation.full.id").value(fullId));
		assertThat(reconciliationRepository.countByMigrationRun_IdAndReconciliationType(
				UUID.fromString(runId), com.etp.migration.entity.ReconciliationType.FULL)).isEqualTo(1);
		mockMvc.perform(get("/api/v1/migration-runs/{runId}/reconciliation", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.reconciliation.full.id").value(fullId));
		mockMvc.perform(get("/api/v1/migration-runs/{runId}", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"));
	}

	@Test
	void detectsPostgresqlStagingCountMismatch() throws Exception {
		String runId = processTwoRowRun();
		try (Connection connection = targetDataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"DELETE FROM migration_staging.address_load WHERE migration_run_id = ? AND source_record_number = 1")) {
			statement.setObject(1, UUID.fromString(runId));
			statement.executeUpdate();
		}
		PhaseAwait.reconciled(mockMvc, runId)
				.andExpect(jsonPath("$.status").value("FAILED"))
				.andExpect(jsonPath("$.reconciliation.count.details.errorCode").value("MIGRATION_TARGET_COUNT_MISMATCH"))
				.andExpect(jsonPath("$.reconciliation.target.status").value("FAILED"));
		mockMvc.perform(get("/api/v1/migration-runs/{runId}", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"));
	}

	@Test
	void rejectsReconciliationWhileBatchesAreActive() throws Exception {
		String objectKey = "samples/pg-recon-active-" + System.nanoTime() + ".csv";
		writeCsv(objectKey, """
				addr1,city
				123 Main St,Salem
				""");
		String mappingId = createMapping();
		String definitionId = createActiveDefinition(mappingId);
		createSource(definitionId, objectKey);
		String runId = createRun(definitionId);
		prepare(runId);
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/reconcile", runId).with(jwt()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("MIGRATION_RECONCILIATION_NOT_READY"));
	}

	private String processTwoRowRun() throws Exception {
		String objectKey = "samples/pg-recon-" + System.nanoTime() + ".csv";
		writeCsv(objectKey, """
				addr1,city
				123 Main St,Salem
				456 Oak Ave,Knoxville
				""");
		String mappingId = createMapping();
		String definitionId = createActiveDefinition(mappingId);
		createSource(definitionId, objectKey);
		String runId = createRun(definitionId);
		prepare(runId);
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/process-next-batch", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"));
		return runId;
	}

	@Test
	void postgresqlSingleModeFailsCleanly() throws Exception {
		String objectKey = "samples/pg-single-" + System.nanoTime() + ".csv";
		writeCsv(objectKey, """
				addr1,city
				123 Main St,Salem
				""");
		String mappingId = createMapping();
		MvcResult created = mockMvc.perform(post("/api/v1/migrations")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "phase10-single-%s",
								  "version": "1.0.0",
								  "sourceType": "FILE",
								  "targetType": "POSTGRESQL",
								  "targetEntity": "address",
								  "loadMode": "SINGLE",
								  "batchSize": 10000,
								  "mappingId": "%s",
								  "configuration": {
								    "target": {
								      "schema": "migration_staging",
								      "stagingTable": "address_load",
								      "mergeStrategy": "NONE"
								    }
								  }
								}
								""".formatted(System.nanoTime(), mappingId)))
				.andExpect(status().isCreated())
				.andReturn();
		String definitionId = readId(created);
		transactionTemplate.executeWithoutResult(tx -> {
			MigrationDefinition definition = migrationDefinitionRepository.findById(UUID.fromString(definitionId)).orElseThrow();
			definition.setStatus(MigrationConfigStatus.ACTIVE);
		});
		createSource(definitionId, objectKey);
		String runId = createRun(definitionId);
		prepare(runId);
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/process-next-batch", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED"));
	}

	private String createMapping() throws Exception {
		MvcResult created = mockMvc.perform(post("/api/v1/mappings")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "phase10-%s",
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

	private String createActiveDefinition(String mappingId) throws Exception {
		MvcResult created = mockMvc.perform(post("/api/v1/migrations")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "phase10-def-%s",
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
								    }
								  }
								}
								""".formatted(System.nanoTime(), mappingId)))
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

	private void prepare(String runId) throws Exception {
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/prepare", runId).with(jwt()))
				.andExpect(status().isOk());
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
