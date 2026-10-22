package com.etp.migration.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.etp.migration.connector.target.postgresql.PostgreSqlTargetClientConfig;
import com.etp.migration.entity.MigrationConfigStatus;
import com.etp.migration.entity.MigrationDefinition;
import com.etp.migration.entity.MigrationPromotion;
import com.etp.migration.entity.MigrationRun;
import com.etp.migration.entity.PromotionStatus;
import com.etp.migration.processing.MigrationErrorCodes;
import com.etp.migration.run.PhaseAwait;
import com.etp.migration.repository.MigrationDefinitionRepository;
import com.etp.migration.repository.MigrationPromotionRepository;
import com.etp.migration.repository.MigrationRunRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
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
class BusinessTargetReconciliationIntegrationTest {

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
	private MigrationRunRepository migrationRunRepository;
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
	void stagingReconciliationRemainsUnchangedBeforePromotion() throws Exception {
		String runId = stage(upsertConfig("external_id"), 10_000, sevenRows(), true);
		PhaseAwait.reconciled(mockMvc, runId)
				.andExpect(jsonPath("$.reconciliation.target.details.verificationScope").value("STAGING"))
				.andExpect(jsonPath("$.reconciliation.target.details.businessTargetVerified").value(false))
				.andExpect(jsonPath("$.reconciliation.target.details.targetCount").value(7))
				.andExpect(jsonPath("$.reconciliation.target.details.mergeStrategy").value("NONE"));
	}

	@Test
	void successfulUpsertPromotionVerifiesBusinessTarget() throws Exception {
		String runId = promote(upsertConfig("external_id"), 10_000, sevenRows());
		insertBusiness("UNRELATED", "Other", "Portland");
		PhaseAwait.reconciled(mockMvc, runId, "BUSINESS_TARGET")
				.andExpect(jsonPath("$.reconciliation.target.status").value("PASSED"))
				.andExpect(jsonPath("$.reconciliation.target.sourceCount").value(7))
				.andExpect(jsonPath("$.reconciliation.target.details.targetCount").value(7))
				.andExpect(jsonPath("$.reconciliation.target.details.difference").value(0))
				.andExpect(jsonPath("$.reconciliation.target.details.verificationScope").value("BUSINESS_TARGET"))
				.andExpect(jsonPath("$.reconciliation.target.details.businessTargetVerified").value(true))
				.andExpect(jsonPath("$.reconciliation.full.details.targetStatus").value("PASSED"));
		assertThat(countBusiness()).isEqualTo(8);
	}

	@Test
	void upsertOfExistingBusinessRowStillVerifies() throws Exception {
		insertBusiness("E1", "Old Street", "Salem");
		String runId = promote(upsertConfig("external_id"), 10_000, """
				external_id,addr1,city
				E1,New Street,Salem
				""");
		PhaseAwait.reconciled(mockMvc, runId, "BUSINESS_TARGET")
				.andExpect(jsonPath("$.reconciliation.target.status").value("PASSED"))
				.andExpect(jsonPath("$.reconciliation.target.details.businessTargetVerified").value(true));
	}

	@Test
	void partialPromotionVerifiesOnlyPromotedPopulation() throws Exception {
		recreateAddress("UNIQUE (city)");
		String runId = promote(insertWithKeys("external_id"), 1, """
				external_id,addr1,city
				P1,123 Main St,Salem
				P2,456 Oak Ave,Salem
				P3,789 Pine Rd,Knoxville
				""");
		assertPromotionStatus(runId, PromotionStatus.PARTIALLY_PROMOTED);
		String runStatus = runStatus(runId);
		String promotionStatus = promotionStatus(runId);
		PhaseAwait.reconciled(mockMvc, runId, "BUSINESS_TARGET")
				.andExpect(jsonPath("$.reconciliation.target.status").value("PASSED"))
				.andExpect(jsonPath("$.reconciliation.target.sourceCount").value(2))
				.andExpect(jsonPath("$.reconciliation.target.details.targetCount").value(2))
				.andExpect(jsonPath("$.reconciliation.target.details.expectedCount").value(2))
				.andExpect(jsonPath("$.reconciliation.target.details.businessTargetVerified").value(true));
		assertThat(runStatus(runId)).isEqualTo(runStatus);
		assertThat(promotionStatus(runId)).isEqualTo(promotionStatus);
	}

	@Test
	void missingBusinessRowFailsTargetVerification() throws Exception {
		String runId = promote(upsertConfig("external_id"), 10_000, """
				external_id,addr1,city
				E1,123 Main St,Salem
				E2,456 Oak Ave,Knoxville
				""");
		try (Connection connection = targetDataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"DELETE FROM promo_app.address WHERE external_id = 'E2'")) {
			statement.executeUpdate();
		}
		PhaseAwait.reconciled(mockMvc, runId, "BUSINESS_TARGET")
				.andExpect(jsonPath("$.reconciliation.target.status").value("FAILED"))
				.andExpect(jsonPath("$.reconciliation.target.sourceCount").value(2))
				.andExpect(jsonPath("$.reconciliation.target.details.targetCount").value(1))
				.andExpect(jsonPath("$.reconciliation.target.details.difference").value(1))
				.andExpect(jsonPath("$.reconciliation.target.details.businessTargetVerified").value(false))
				.andExpect(jsonPath("$.reconciliation.full.status").value("FAILED"));
	}

	@Test
	void configuredValueMismatchFailsTargetVerification() throws Exception {
		String runId = promote(upsertConfig("external_id"), 10_000, """
				external_id,addr1,city
				E1,123 Main St,Salem
				""");
		try (Connection connection = targetDataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"UPDATE promo_app.address SET city = 'Changed' WHERE external_id = 'E1'")) {
			statement.executeUpdate();
		}
		PhaseAwait.reconciled(mockMvc, runId, "BUSINESS_TARGET")
				.andExpect(jsonPath("$.reconciliation.target.status").value("FAILED"))
				.andExpect(jsonPath("$.reconciliation.target.details.errorCode")
						.value(MigrationErrorCodes.TARGET_VALUE_MISMATCH))
				.andExpect(jsonPath("$.reconciliation.target.details.businessTargetVerified").value(false));
	}

	@Test
	void compositeConflictKeysVerify() throws Exception {
		recreateAddress("UNIQUE (external_id, city)");
		String runId = promote(upsertConfig("external_id", "city"), 10_000, """
				external_id,addr1,city
				C1,123 Main St,Salem
				""");
		PhaseAwait.reconciled(mockMvc, runId, "BUSINESS_TARGET")
				.andExpect(jsonPath("$.reconciliation.target.status").value("PASSED"))
				.andExpect(jsonPath("$.reconciliation.target.details.businessTargetVerified").value(true));
	}

	@Test
	void insertWithUsableConflictKeyVerifies() throws Exception {
		String runId = promote(insertWithKeys("external_id"), 10_000, """
				external_id,addr1,city
				I1,123 Main St,Salem
				""");
		PhaseAwait.reconciled(mockMvc, runId, "BUSINESS_TARGET")
				.andExpect(jsonPath("$.reconciliation.target.status").value("PASSED"))
				.andExpect(jsonPath("$.reconciliation.target.details.businessTargetVerified").value(true));
	}

	@Test
	void insertWithoutConflictKeyWarnsIdentityNotConfigured() throws Exception {
		String runId = promote(insertConfig(), 10_000, """
				external_id,addr1,city
				I2,123 Main St,Salem
				""");
		PhaseAwait.reconciled(mockMvc, runId, "BUSINESS_TARGET")
				.andExpect(jsonPath("$.reconciliation.target.status").value("PASSED_WITH_WARNINGS"))
				.andExpect(jsonPath("$.reconciliation.target.details.businessTargetVerified").value(false))
				.andExpect(jsonPath("$.reconciliation.target.details.errorCode")
						.value(MigrationErrorCodes.TARGET_IDENTITY_NOT_CONFIGURED));
	}

	@Test
	void duplicateMigrationIdentityDoesNotPass() throws Exception {
		recreateAddress("CHECK (id IS NOT NULL)");
		String runId = promote(insertWithKeys("external_id"), 10_000, """
				external_id,addr1,city
				D1,123 Main St,Salem
				D1,456 Oak Ave,Knoxville
				""");
		PhaseAwait.reconciled(mockMvc, runId, "BUSINESS_TARGET")
				.andExpect(jsonPath("$.reconciliation.target.status").value("FAILED"))
				.andExpect(jsonPath("$.reconciliation.target.details.errorCode")
						.value(MigrationErrorCodes.TARGET_IDENTITY_AMBIGUOUS))
				.andExpect(jsonPath("$.reconciliation.target.details.businessTargetVerified").value(false));
	}

	@Test
	void generatedApplicationColumnsAreIgnored() throws Exception {
		String runId = promote(upsertConfig("external_id"), 10_000, """
				external_id,addr1,city
				G1,123 Main St,Salem
				""");
		try (Connection connection = targetDataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"UPDATE promo_app.address SET created_at = created_at - INTERVAL '1 day' WHERE external_id = 'G1'")) {
			statement.executeUpdate();
		}
		PhaseAwait.reconciled(mockMvc, runId, "BUSINESS_TARGET")
				.andExpect(jsonPath("$.reconciliation.target.status").value("PASSED"))
				.andExpect(jsonPath("$.reconciliation.target.details.businessTargetVerified").value(true));
	}

	@Test
	void businessVerificationBeforePromotionIsRejected() throws Exception {
		String runId = stage(upsertConfig("external_id"), 10_000, """
				external_id,addr1,city
				B1,123 Main St,Salem
				""", true);
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/reconcile", runId)
						.param("verificationScope", "BUSINESS_TARGET")
						.with(jwt()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value(MigrationErrorCodes.PROMOTION_NOT_EXECUTED));
	}

	private String sevenRows() {
		return """
				external_id,addr1,city
				E1,123 Main St,Salem
				E2,456 Oak Ave,Knoxville
				E3,789 Pine Rd,Nashville
				E4,101 Elm St,Memphis
				E5,202 Cedar Ave,Chattanooga
				E6,303 Birch Blvd,Clarksville
				E7,404 Maple Dr,Murfreesboro
				""";
	}

	private String promote(String promotionJson, int batchSize, String csv) throws Exception {
		String runId = stage(promotionJson, batchSize, csv, true);
		PhaseAwait.prepared(mockMvc, runId);
		PhaseAwait.promoted(mockMvc, runId);
		return runId;
	}

	private String stage(String promotionJson, int batchSize, String csv, boolean reconcile) throws Exception {
		String objectKey = "samples/promo-recon-" + System.nanoTime() + ".csv";
		writeCsv(objectKey, csv);
		String mappingId = createMapping();
		String definitionId = createActiveDefinition(mappingId, batchSize, promotionJson);
		createSource(definitionId, objectKey);
		String runId = createRun(definitionId);
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/prepare", runId).with(jwt())).andExpect(status().isOk());
		processAll(runId);
		if (reconcile) {
			PhaseAwait.reconciled(mockMvc, runId);
		}
		return runId;
	}

	private void processAll(String runId) throws Exception {
		while (true) {
			MvcResult result = mockMvc.perform(post("/api/v1/migration-runs/{runId}/process-next-batch", runId).with(jwt()))
					.andReturn();
			if (result.getResponse().getStatus() == 409) {
				return;
			}
			assertThat(result.getResponse().getStatus()).isEqualTo(200);
		}
	}

	private String createMapping() throws Exception {
		MvcResult created = mockMvc.perform(post("/api/v1/mappings")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "phase13d-%s",
								  "version": "1.0.0",
								  "sourceType": "FILE",
								  "targetType": "POSTGRESQL"
								}
								""".formatted(System.nanoTime())))
				.andExpect(status().isCreated())
				.andReturn();
		String mappingId = readId(created);
		addRule(mappingId, 1, "external_id", "external_id", "DIRECT", false);
		addRule(mappingId, 2, "addr1", "address_line_1", "TRIM", true);
		addRule(mappingId, 3, "city", "city", "DIRECT", false);
		return mappingId;
	}

	private void addRule(String mappingId, int sequence, String source, String target, String type, boolean mandatory)
			throws Exception {
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
								""".formatted(sequence, source, target, type, mandatory)))
				.andExpect(status().isCreated());
	}

	private String createActiveDefinition(String mappingId, int batchSize, String promotionJson) throws Exception {
		MvcResult created = mockMvc.perform(post("/api/v1/migrations")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "phase13d-def-%s",
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

	private String insertWithKeys(String... conflictKeys) {
		return promotionConfig("INSERT", conflictKeys);
	}

	private String upsertConfig(String... conflictKeys) {
		return promotionConfig("UPSERT", conflictKeys);
	}

	private String promotionConfig(String strategy, String... conflictKeys) {
		List<String> quoted = new ArrayList<>();
		for (String key : conflictKeys) {
			quoted.add("\"" + key + "\"");
		}
		String keys = quoted.isEmpty() ? "" : ", \"conflictKeys\": [" + String.join(",", quoted) + "]";
		return """
				{
				  "enabled": true,
				  "schema": "promo_app",
				  "table": "address",
				  "strategy": "%s",
				  "columns": {
				    "external_id": "external_id",
				    "address_line_1": "address_line_1",
				    "city": "city"
				  }%s
				}
				""".formatted(strategy, keys);
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

	private String runStatus(String runId) {
		return transactionTemplate.execute(tx ->
				migrationRunRepository.findById(UUID.fromString(runId)).map(MigrationRun::getStatus).orElseThrow().name());
	}

	private String promotionStatus(String runId) {
		return transactionTemplate.execute(tx -> promotionRepository.findByMigrationRun_IdOrderByCreatedAtDesc(
				UUID.fromString(runId)).getFirst().getStatus().name());
	}

	private void assertPromotionStatus(String runId, PromotionStatus expected) {
		assertThat(promotionStatus(runId)).isEqualTo(expected.name());
	}

	private String readId(MvcResult result) throws Exception {
		return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id");
	}
}
