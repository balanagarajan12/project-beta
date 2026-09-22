package com.etp.migration.promotion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.etp.migration.connector.target.postgresql.PostgreSqlTargetClientConfig;
import com.etp.migration.entity.MigrationBatch;
import com.etp.migration.entity.MigrationBatchStatus;
import com.etp.migration.entity.MigrationConfigStatus;
import com.etp.migration.entity.MigrationDefinition;
import com.etp.migration.entity.MigrationPromotion;
import com.etp.migration.entity.PromotionStatus;
import com.etp.migration.processing.MigrationErrorCodes;
import com.etp.migration.repository.MigrationBatchRepository;
import com.etp.migration.repository.MigrationDefinitionRepository;
import com.etp.migration.repository.MigrationPromotionRepository;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
class PromotionExecutionIntegrationTest {

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
	private MigrationPromotionRepository promotionRepository;
	@Autowired
	private TransactionTemplate transactionTemplate;
	@Autowired
	@Qualifier(PostgreSqlTargetClientConfig.DATASOURCE_BEAN)
	private DataSource targetDataSource;
	@MockitoBean
	private JwtDecoder jwtDecoder;
	private String lastDefinitionId;

	@BeforeEach
	void createTables() throws Exception {
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
			statement.execute("CREATE SCHEMA IF NOT EXISTS promo_app");
			recreateAddressTable(statement, "UNIQUE (external_id)");
		}
	}

	@Test
	void insertPromotesOneBatchSuccessfully() throws Exception {
		String runId = stageAndPrepare(insertConfig(), 10_000, """
				external_id,addr1,city
				E1,123 Main St,Salem
				E2,456 Oak Ave,Knoxville
				""");
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/promote", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PROMOTED"))
				.andExpect(jsonPath("$.promotedBatches").value(1))
				.andExpect(jsonPath("$.promotedRecords").value(2))
				.andExpect(jsonPath("$.failedRecords").value(0));
		assertThat(countBusiness()).isEqualTo(2);
		assertGeneratedColumnsPresent();
	}

	@Test
	void getReturnsPromotedPromotionWithoutMutation() throws Exception {
		String runId = stageAndPrepare(insertConfig(), 10_000, """
				external_id,addr1,city
				G1,123 Main St,Salem
				G2,456 Oak Ave,Knoxville
				""");
		MvcResult promoted = mockMvc.perform(post("/api/v1/migration-runs/{runId}/promote", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PROMOTED"))
				.andReturn();
		String promotionId = com.jayway.jsonpath.JsonPath.read(promoted.getResponse().getContentAsString(), "$.promotionId");
		Instant updatedAt = transactionTemplate.execute(tx ->
				promotionRepository.findById(UUID.fromString(promotionId)).orElseThrow().getUpdatedAt());
		PromotionStatus status = transactionTemplate.execute(tx ->
				promotionRepository.findById(UUID.fromString(promotionId)).orElseThrow().getStatus());

		mockMvc.perform(get("/api/v1/migration-runs/{runId}/promotion", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.promotionId").value(promotionId))
				.andExpect(jsonPath("$.status").value("PROMOTED"))
				.andExpect(jsonPath("$.promotedBatches").value(1))
				.andExpect(jsonPath("$.promotedRecords").value(2))
				.andExpect(jsonPath("$.failedRecords").value(0))
				.andExpect(jsonPath("$.batches[0].promotionStatus").value("PROMOTED"))
				.andExpect(jsonPath("$.batches[0].promotedRecordCount").value(2));

		MigrationPromotion afterGet = transactionTemplate.execute(tx ->
				promotionRepository.findById(UUID.fromString(promotionId)).orElseThrow());
		assertThat(afterGet.getUpdatedAt()).isEqualTo(updatedAt);
		assertThat(afterGet.getStatus()).isEqualTo(status);
		assertThat(countBusiness()).isEqualTo(2);
	}

	@Test
	void getReturnsPartialPromotionBatchState() throws Exception {
		recreateAddress("UNIQUE (city)");
		String runId = stageAndPrepare(insertConfig(), 1, """
				external_id,addr1,city
				Q1,123 Main St,Salem
				Q2,456 Oak Ave,Salem
				""");
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/promote", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PARTIALLY_PROMOTED"));

		mockMvc.perform(get("/api/v1/migration-runs/{runId}/promotion", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PARTIALLY_PROMOTED"))
				.andExpect(jsonPath("$.promotedBatches").value(1))
				.andExpect(jsonPath("$.failedBatches").value(1))
				.andExpect(jsonPath("$.promotedRecords").value(1))
				.andExpect(jsonPath("$.failedRecords").value(1));
	}

	@Test
	void upsertInsertsNewRow() throws Exception {
		String runId = stageAndPrepare(upsertConfig("external_id"), 10_000, """
				external_id,addr1,city
				U1,123 Main St,Salem
				""");
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/promote", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PROMOTED"));
		assertThat(businessCity("U1")).isEqualTo("Salem");
	}

	@Test
	void upsertUpdatesExistingRowWithoutChangingConflictKey() throws Exception {
		insertBusiness("U1", "Old Street", "Salem");
		String runId = stageAndPrepare(upsertConfig("external_id"), 10_000, """
				external_id,addr1,city
				U1,New Street,Knoxville
				""");
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/promote", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PROMOTED"));
		assertThat(countBusiness()).isEqualTo(1);
		assertThat(businessLine("U1")).isEqualTo("New Street");
		assertThat(businessCity("U1")).isEqualTo("Knoxville");
		assertThat(businessExternalId("U1")).isEqualTo("U1");
	}

	@Test
	void promotesMultipleEligibleBatches() throws Exception {
		String runId = stageAndPrepare(insertConfig(), 1, """
				external_id,addr1,city
				M1,123 Main St,Salem
				M2,456 Oak Ave,Knoxville
				""");
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/promote", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PROMOTED"))
				.andExpect(jsonPath("$.promotedBatches").value(2))
				.andExpect(jsonPath("$.promotedRecords").value(2));
		assertThat(countBusiness()).isEqualTo(2);
	}

	@Test
	void skipsIneligibleBatchesAndValidationFailures() throws Exception {
		String runId = stageAndPrepare(insertConfig(), 1, """
				external_id,addr1,city
				V1,123 Main St,Salem
				V2,,Knoxville
				""");
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/promote", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PROMOTED"))
				.andExpect(jsonPath("$.promotedBatches").value(1))
				.andExpect(jsonPath("$.skippedBatches").value(1))
				.andExpect(jsonPath("$.promotedRecords").value(1));
		assertThat(countBusiness()).isEqualTo(1);
		assertThat(businessExternalId("V1")).isEqualTo("V1");
		assertThat(countBusinessByExternalId("V2")).isZero();
	}

	@Test
	void upsertSupportsCompositeConflictKeys() throws Exception {
		recreateAddress("UNIQUE (external_id, city)");
		insertBusiness("C1", "Old Street", "Salem");
		String runId = stageAndPrepare(upsertConfig("external_id", "city"), 10_000, """
				external_id,addr1,city
				C1,New Street,Salem
				""");
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/promote", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PROMOTED"));
		assertThat(countBusiness()).isEqualTo(1);
		assertThat(businessLine("C1")).isEqualTo("New Street");
	}

	@Test
	void failedBatchRollsBackCompletely() throws Exception {
		recreateAddress("UNIQUE (city)");
		String runId = stageAndPrepare(insertConfig(), 10_000, """
				external_id,addr1,city
				R1,123 Main St,Salem
				R2,456 Oak Ave,Salem
				""");
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/promote", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PROMOTION_FAILED"))
				.andExpect(jsonPath("$.promotedRecords").value(0))
				.andExpect(jsonPath("$.failedRecords").value(2))
				.andExpect(jsonPath("$.failedBatches").value(1));
		assertThat(countBusiness()).isZero();
	}

	@Test
	void failedBatchDoesNotRollbackPreviouslySuccessfulBatches() throws Exception {
		recreateAddress("UNIQUE (city)");
		String runId = stageAndPrepare(insertConfig(), 1, """
				external_id,addr1,city
				P1,123 Main St,Salem
				P2,456 Oak Ave,Salem
				""");
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/promote", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PARTIALLY_PROMOTED"))
				.andExpect(jsonPath("$.promotedBatches").value(1))
				.andExpect(jsonPath("$.failedBatches").value(1))
				.andExpect(jsonPath("$.promotedRecords").value(1))
				.andExpect(jsonPath("$.failedRecords").value(1));
		assertThat(countBusiness()).isEqualTo(1);
		assertThat(businessCity("P1")).isEqualTo("Salem");
	}

	@Test
	void rerunningPromotedPromotionIsIdempotent() throws Exception {
		String runId = stageAndPrepare(insertConfig(), 10_000, """
				external_id,addr1,city
				I1,123 Main St,Salem
				""");
		MvcResult first = mockMvc.perform(post("/api/v1/migration-runs/{runId}/promote", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PROMOTED"))
				.andReturn();
		String promotionId = com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(), "$.promotionId");
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/promote", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.promotionId").value(promotionId))
				.andExpect(jsonPath("$.status").value("PROMOTED"));
		assertThat(countBusiness()).isEqualTo(1);
	}

	@Test
	void concurrentPromotionAttemptsAreRejected() throws Exception {
		StringBuilder csv = new StringBuilder("external_id,addr1,city\n");
		for (int i = 0; i < 200; i++) {
			csv.append("X").append(i).append(",Street ").append(i).append(",City").append(i).append("\n");
		}
		String runId = stageAndPrepare(insertConfig(), 10_000, csv.toString());
		CyclicBarrier barrier = new CyclicBarrier(2);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Callable<Integer> task = () -> {
				barrier.await();
				return mockMvc.perform(post("/api/v1/migration-runs/{runId}/promote", runId).with(jwt()))
						.andReturn()
						.getResponse()
						.getStatus();
			};
			Future<Integer> first = executor.submit(task);
			Future<Integer> second = executor.submit(task);
			List<Integer> statuses = List.of(first.get(), second.get());
			assertThat(statuses).contains(200);
			assertThat(statuses).contains(409);
		} finally {
			executor.shutdownNow();
		}
		assertThat(countBusiness()).isEqualTo(200);
	}

	@Test
	void rejectsPromotionWhileExecutionBatchIsActive() throws Exception {
		String runId = stageAndPrepare(insertConfig(), 10_000, """
				external_id,addr1,city
				A1,123 Main St,Salem
				""");
		transactionTemplate.executeWithoutResult(tx -> {
			List<MigrationBatch> batches = migrationBatchRepository.findByMigrationRun_IdOrderByBatchNumberAsc(
					UUID.fromString(runId));
			batches.getFirst().setStatus(MigrationBatchStatus.CLAIMED);
		});
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/promote", runId).with(jwt()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value(MigrationErrorCodes.PROMOTION_ACTIVE_BATCHES));
	}

	@Test
	void rejectsPromotionWithoutReconciliation() throws Exception {
		String runId = stage(insertConfig(), 10_000, """
				external_id,addr1,city
				N1,123 Main St,Salem
				""", false);
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/promote", runId).with(jwt()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value(MigrationErrorCodes.PROMOTION_RECONCILIATION_REQUIRED));
	}

	@Test
	void rejectsPromotionWithoutReadyOperation() throws Exception {
		String runId = stage(insertConfig(), 10_000, """
				external_id,addr1,city
				N2,123 Main St,Salem
				""", true);
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/promote", runId).with(jwt()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value(MigrationErrorCodes.PROMOTION_NOT_READY));
	}

	@Test
	void rejectsInvalidPromotionConfiguration() throws Exception {
		String runId = stageAndPrepare(insertConfig(), 10_000, """
				external_id,addr1,city
				N3,123 Main St,Salem
				""");
		transactionTemplate.executeWithoutResult(tx -> {
			MigrationDefinition definition = migrationDefinitionRepository.findById(UUID.fromString(lastDefinitionId)).orElseThrow();
			java.util.Map<String, Object> configuration = new java.util.HashMap<>(definition.getConfiguration());
			configuration.put("promotion", java.util.Map.of(
					"enabled", true,
					"schema", "bad-schema",
					"table", "address",
					"strategy", "INSERT",
					"columns", java.util.Map.of("external_id", "external_id")));
			definition.setConfiguration(configuration);
		});
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/promote", runId).with(jwt()))
				.andExpect(status().isBadRequest());
	}

	private String stageAndPrepare(String promotionJson, int batchSize, String csv) throws Exception {
		String runId = stage(promotionJson, batchSize, csv, true);
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/promotion/prepare", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("READY"));
		return runId;
	}

	private String stage(String promotionJson, int batchSize, String csv, boolean reconcile) throws Exception {
		String objectKey = "samples/promo-exec-" + System.nanoTime() + ".csv";
		writeCsv(objectKey, csv);
		String mappingId = createMapping();
		String definitionId = createActiveDefinition(mappingId, batchSize, promotionJson);
		createSource(definitionId, objectKey);
		String runId = createRun(definitionId);
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/prepare", runId).with(jwt())).andExpect(status().isOk());
		processAll(runId);
		if (reconcile) {
			mockMvc.perform(post("/api/v1/migration-runs/{runId}/reconcile", runId).with(jwt())).andExpect(status().isOk());
		}
		return runId;
	}

	private void processAll(String runId) throws Exception {
		while (true) {
			MvcResult result = mockMvc.perform(post("/api/v1/migration-runs/{runId}/process-next-batch", runId).with(jwt()))
					.andReturn();
			int status = result.getResponse().getStatus();
			if (status == 409) {
				return;
			}
			assertThat(status).isEqualTo(200);
		}
	}

	private String createMapping() throws Exception {
		MvcResult created = mockMvc.perform(post("/api/v1/mappings")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "phase13c-%s",
								  "version": "1.0.0",
								  "sourceType": "FILE",
								  "targetType": "POSTGRESQL"
								}
								""".formatted(System.nanoTime())))
				.andExpect(status().isCreated())
				.andReturn();
		String mappingId = readId(created);
		addRule(mappingId, 1, "external_id", "external_id", "DIRECT");
		addRule(mappingId, 2, "addr1", "address_line_1", "TRIM");
		addRule(mappingId, 3, "city", "city", "DIRECT");
		return mappingId;
	}

	private void addRule(String mappingId, int sequence, String source, String target, String type) throws Exception {
		mockMvc.perform(post("/api/v1/mappings/{id}/rules", mappingId)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "sequenceNo": %s,
								  "sourceField": "%s",
								  "targetField": "%s",
								  "transformationType": "%s",
								  "mandatory": %s,
								  "enabled": true
								}
								""".formatted(sequence, source, target, type, "address_line_1".equals(target))))
				.andExpect(status().isCreated());
	}

	private String createActiveDefinition(String mappingId, int batchSize, String promotionJson) throws Exception {
		MvcResult created = mockMvc.perform(post("/api/v1/migrations")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "phase13c-def-%s",
								  "version": "1.0.0",
								  "sourceType": "FILE",
								  "targetType": "POSTGRESQL",
								  "targetEntity": "address",
								  "loadMode": "BULK",
								  "batchSize": %s,
								  "mappingId": "%s",
								  "configuration": {
								    "target": {
								      "schema": "migration_staging",
								      "stagingTable": "address_load",
								      "mergeStrategy": "NONE"
								    },
								    "promotion": %s
								  }
								}
								""".formatted(System.nanoTime(), batchSize, mappingId, promotionJson)))
				.andExpect(status().isCreated())
				.andReturn();
		String id = readId(created);
		this.lastDefinitionId = id;
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

	private String insertConfig() {
		return """
				{
				  "enabled": true,
				  "schema": "promo_app",
				  "table": "address",
				  "strategy": "INSERT",
				  "columns": {
				    "external_id": "external_id",
				    "address_line_1": "address_line_1",
				    "city": "city"
				  }
				}
				""";
	}

	private String upsertConfig(String... conflictKeys) {
		List<String> quoted = new ArrayList<>();
		for (String key : conflictKeys) {
			quoted.add("\"" + key + "\"");
		}
		return """
				{
				  "enabled": true,
				  "schema": "promo_app",
				  "table": "address",
				  "strategy": "UPSERT",
				  "columns": {
				    "external_id": "external_id",
				    "address_line_1": "address_line_1",
				    "city": "city"
				  },
				  "conflictKeys": [%s]
				}
				""".formatted(String.join(",", quoted));
	}

	private void recreateAddress(String uniqueClause) throws Exception {
		try (Connection connection = targetDataSource.getConnection(); Statement statement = connection.createStatement()) {
			recreateAddressTable(statement, uniqueClause);
		}
	}

	private void recreateAddressTable(Statement statement, String uniqueClause) throws Exception {
		statement.execute("DROP TABLE IF EXISTS promo_app.address");
		statement.execute("""
				CREATE TABLE promo_app.address (
				  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
				  external_id TEXT,
				  address_line_1 TEXT,
				  city TEXT,
				  state TEXT,
				  postal_code TEXT,
				  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
				  %s
				)
				""".formatted(uniqueClause));
	}

	private void insertBusiness(String externalId, String line, String city) throws Exception {
		try (Connection connection = targetDataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"INSERT INTO promo_app.address (external_id, address_line_1, city) VALUES (?, ?, ?)")) {
			statement.setString(1, externalId);
			statement.setString(2, line);
			statement.setString(3, city);
			statement.executeUpdate();
		}
	}

	private int countBusiness() throws Exception {
		try (Connection connection = targetDataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM promo_app.address");
				ResultSet rs = statement.executeQuery()) {
			rs.next();
			return rs.getInt(1);
		}
	}

	private int countBusinessByExternalId(String externalId) throws Exception {
		try (Connection connection = targetDataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT COUNT(*) FROM promo_app.address WHERE external_id = ?")) {
			statement.setString(1, externalId);
			try (ResultSet rs = statement.executeQuery()) {
				rs.next();
				return rs.getInt(1);
			}
		}
	}

	private String businessCity(String externalId) throws Exception {
		return scalar("SELECT city FROM promo_app.address WHERE external_id = ?", externalId);
	}

	private String businessLine(String externalId) throws Exception {
		return scalar("SELECT address_line_1 FROM promo_app.address WHERE external_id = ?", externalId);
	}

	private String businessExternalId(String externalId) throws Exception {
		return scalar("SELECT external_id FROM promo_app.address WHERE external_id = ?", externalId);
	}

	private String scalar(String sql, String externalId) throws Exception {
		try (Connection connection = targetDataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, externalId);
			try (ResultSet rs = statement.executeQuery()) {
				assertThat(rs.next()).isTrue();
				return rs.getString(1);
			}
		}
	}

	private void assertGeneratedColumnsPresent() throws Exception {
		try (Connection connection = targetDataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT id, created_at FROM promo_app.address");
				ResultSet rs = statement.executeQuery()) {
			assertThat(rs.next()).isTrue();
			assertThat(rs.getLong("id")).isPositive();
			assertThat(rs.getTimestamp("created_at")).isNotNull();
		}
	}

	private String readId(MvcResult result) throws Exception {
		return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id");
	}
}
