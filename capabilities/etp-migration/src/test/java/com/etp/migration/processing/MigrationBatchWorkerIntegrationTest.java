package com.etp.migration.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.etp.migration.entity.LoadMode;
import com.etp.migration.entity.MigrationConfigStatus;
import com.etp.migration.entity.MigrationDefinition;
import com.etp.migration.repository.MigrationDefinitionRepository;
import com.etp.migration.repository.MigrationErrorRepository;
import com.etp.migration.repository.MigrationRecordRepository;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.client.WireMock;

@SpringBootTest
@AutoConfigureMockMvc
class MigrationBatchWorkerIntegrationTest {

	@TempDir
	static Path storageRoot;

	@RegisterExtension
	static WireMockExtension uam = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort())
			.build();

	@DynamicPropertySource
	static void storageProperties(DynamicPropertyRegistry registry) {
		registry.add("etp.migration.storage.root", () -> storageRoot.toAbsolutePath().toString());
		registry.add("etp.migration.target.uam.base-url", () -> uam.getRuntimeInfo().getHttpBaseUrl());
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private MigrationDefinitionRepository migrationDefinitionRepository;

	@Autowired
	private MigrationErrorRepository migrationErrorRepository;

	@Autowired
	private MigrationRecordRepository migrationRecordRepository;

	@Autowired
	private TransactionTemplate transactionTemplate;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	@BeforeEach
	void stubUamSuccess() {
		uam.resetAll();
		uam.stubFor(WireMock.post(urlPathEqualTo("/api/v1/address"))
				.willReturn(aResponse()
						.withStatus(201)
						.withHeader("Content-Type", "application/json")
						.withBody("{\"id\":\"addr-1\"}")));
	}

	@Test
	void processesBatchesAndCompletesRunWithoutPersistingSuccessfulRecords() throws Exception {
		String objectKey = "samples/worker-" + System.nanoTime() + ".csv";
		writeCsv(objectKey, """
				addr1,city
				123 Main St,Salem
				456 Oak Ave,Knoxville
				""");
		String mappingId = createMapping(true);
		String definitionId = createActiveDefinition(mappingId, 10_000, "UAM", "SINGLE");
		createSource(definitionId, objectKey);
		String runId = createRun(definitionId);
		prepare(runId);

		mockMvc.perform(post("/api/v1/migration-runs/{runId}/process-next-batch", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.totalRecords").value(2))
				.andExpect(jsonPath("$.successRecords").value(2));

		mockMvc.perform(get("/api/v1/migration-runs/{runId}", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.successRecords").value(2));

		assertThat(migrationRecordRepository.countByMigrationRun_Id(UUID.fromString(runId))).isZero();
		assertThat(migrationErrorRepository.countByMigrationRun_Id(UUID.fromString(runId))).isZero();
	}

	@Test
	void recordErrorsCompleteBatchWithErrorsAndDoNotFailTheRunBatch() throws Exception {
		String objectKey = "samples/errors-" + System.nanoTime() + ".csv";
		writeCsv(objectKey, """
				addr1,city
				123 Main St,Salem
				,Knoxville
				""");
		String mappingId = createMapping(true);
		String definitionId = createActiveDefinition(mappingId, 10_000, "UAM", "SINGLE");
		createSource(definitionId, objectKey);
		String runId = createRun(definitionId);
		prepare(runId);

		mockMvc.perform(post("/api/v1/migration-runs/{runId}/process-next-batch", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED_WITH_ERRORS"))
				.andExpect(jsonPath("$.successRecords").value(1))
				.andExpect(jsonPath("$.failedRecords").value(1));

		assertThat(migrationErrorRepository.countByMigrationRun_Id(UUID.fromString(runId))).isEqualTo(1);
		assertThat(migrationRecordRepository.countByMigrationRun_Id(UUID.fromString(runId))).isZero();

		mockMvc.perform(get("/api/v1/migration-runs/{runId}", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED_WITH_ERRORS"));

		mockMvc.perform(post("/api/v1/migration-runs/{runId}/retry", runId).with(jwt()))
				.andExpect(status().isOk());
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/process-next-batch", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED_WITH_ERRORS"));
	}

	@Test
	void doesNotCompleteRunWhileBatchesRemainThenCompletes() throws Exception {
		String objectKey = "samples/multi-" + System.nanoTime() + ".csv";
		writeCsv(objectKey, """
				addr1,city
				a,Salem
				b,Salem
				c,Salem
				""");
		String mappingId = createMapping(true);
		String definitionId = createActiveDefinition(mappingId, 2, "UAM", "SINGLE");
		createSource(definitionId, objectKey);
		String runId = createRun(definitionId);
		prepare(runId);

		mockMvc.perform(post("/api/v1/migration-runs/{runId}/process-next-batch", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"));
		mockMvc.perform(get("/api/v1/migration-runs/{runId}", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("RUNNING"));

		mockMvc.perform(post("/api/v1/migration-runs/{runId}/process-next-batch", runId).with(jwt()))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/migration-runs/{runId}", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"));
	}

	@Test
	void connectorFailureFailsBatchAndRetrySucceedsAfterRestore() throws Exception {
		String objectKey = "samples/fail-" + System.nanoTime() + ".csv";
		Path path = storageRoot.resolve(objectKey);
		writeCsv(objectKey, """
				addr1,city
				123 Main St,Salem
				""");
		String mappingId = createMapping(true);
		String definitionId = createActiveDefinition(mappingId, 10_000, "UAM", "SINGLE");
		createSource(definitionId, objectKey);
		String runId = createRun(definitionId);
		prepare(runId);
		Files.delete(path);

		mockMvc.perform(post("/api/v1/migration-runs/{runId}/process-next-batch", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED"));
		mockMvc.perform(get("/api/v1/migration-runs/{runId}", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED"));

		writeCsv(objectKey, """
				addr1,city
				123 Main St,Salem
				""");
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/retry", runId).with(jwt()))
				.andExpect(status().isOk());
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/process-next-batch", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"));
	}

	@Test
	void missingMappingAndChangedChecksumFailCleanly() throws Exception {
		String objectKey = "samples/checksum-" + System.nanoTime() + ".csv";
		writeCsv(objectKey, """
				addr1,city
				123 Main St,Salem
				""");
		String definitionId = createActiveDefinition(null, 10_000, "UAM", "SINGLE");
		createSource(definitionId, objectKey);
		String runId = createRun(definitionId);
		prepare(runId);
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/process-next-batch", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED"));

		String mappedKey = "samples/changed-" + System.nanoTime() + ".csv";
		writeCsv(mappedKey, """
				addr1,city
				123 Main St,Salem
				""");
		String mappingId = createMapping(true);
		String mappedDefinition = createActiveDefinition(mappingId, 10_000, "UAM", "SINGLE");
		createSource(mappedDefinition, mappedKey);
		String mappedRun = createRun(mappedDefinition);
		prepare(mappedRun);
		writeCsv(mappedKey, """
				addr1,city
				999 Other St,Nashville
				""");
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/process-next-batch", mappedRun).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED"));
	}

	@Test
	void recordLevelTargetFailureCompletesWithErrors() throws Exception {
		uam.resetAll();
		uam.stubFor(WireMock.post(urlPathEqualTo("/api/v1/address"))
				.atPriority(1)
				.withRequestBody(matchingJsonPath("$.streetName", equalTo("bad")))
				.willReturn(aResponse().withStatus(400).withBody("{\"error\":\"invalid\"}")));
		uam.stubFor(WireMock.post(urlPathEqualTo("/api/v1/address"))
				.atPriority(10)
				.willReturn(aResponse()
						.withStatus(201)
						.withHeader("Content-Type", "application/json")
						.withBody("{\"id\":\"addr-ok\"}")));
		String objectKey = "samples/target-400-" + System.nanoTime() + ".csv";
		writeCsv(objectKey, """
				addr1,city
				123 Main St,Salem
				bad,Knoxville
				""");
		String mappingId = createMapping(true);
		String definitionId = createActiveDefinition(mappingId, 10_000, "UAM", "SINGLE");
		createSource(definitionId, objectKey);
		String runId = createRun(definitionId);
		prepare(runId);
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/process-next-batch", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED_WITH_ERRORS"))
				.andExpect(jsonPath("$.successRecords").value(1))
				.andExpect(jsonPath("$.failedRecords").value(1));
	}

	@Test
	void batchLevelTargetAuthFailureFailsBatch() throws Exception {
		uam.resetAll();
		uam.stubFor(WireMock.post(urlPathEqualTo("/api/v1/address"))
				.willReturn(aResponse().withStatus(401)));
		String objectKey = "samples/target-401-" + System.nanoTime() + ".csv";
		writeCsv(objectKey, """
				addr1,city
				123 Main St,Salem
				""");
		String mappingId = createMapping(true);
		String definitionId = createActiveDefinition(mappingId, 10_000, "UAM", "SINGLE");
		createSource(definitionId, objectKey);
		String runId = createRun(definitionId);
		prepare(runId);
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/process-next-batch", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED"));
	}

	@Test
	void unsupportedLoadModeFailsCleanly() throws Exception {
		String objectKey = "samples/bulk-" + System.nanoTime() + ".csv";
		writeCsv(objectKey, """
				addr1,city
				123 Main St,Salem
				""");
		String mappingId = createMapping(true);
		String definitionId = createActiveDefinition(mappingId, 10_000, "UAM", "BULK");
		createSource(definitionId, objectKey);
		String runId = createRun(definitionId);
		prepare(runId);
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/process-next-batch", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED"));
	}

	@Test
	void uamReconciliationWarnsWhenTargetVerificationIsUnavailable() throws Exception {
		String objectKey = "samples/uam-recon-" + System.nanoTime() + ".csv";
		writeCsv(objectKey, """
				addr1,city
				123 Main St,Salem
				""");
		String mappingId = createMapping(true);
		String definitionId = createActiveDefinition(mappingId, 10_000, "UAM", "SINGLE");
		createSource(definitionId, objectKey);
		String runId = createRun(definitionId);
		prepare(runId);
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/process-next-batch", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"));
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/reconcile", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PASSED_WITH_WARNINGS"))
				.andExpect(jsonPath("$.reconciliation.target.status").value("PASSED_WITH_WARNINGS"))
				.andExpect(jsonPath("$.reconciliation.target.details.targetVerificationAvailable").value(false))
				.andExpect(jsonPath("$.reconciliation.target.details.verificationScope").value("BUSINESS_TARGET"))
				.andExpect(jsonPath("$.reconciliation.checksum.details.comparisonPerformed").value(false));
		mockMvc.perform(get("/api/v1/migration-runs/{runId}", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"));
	}

	@Test
	void uamProcessingErrorsRemainConsistentInReconciliation() throws Exception {
		String objectKey = "samples/uam-recon-errors-" + System.nanoTime() + ".csv";
		writeCsv(objectKey, """
				addr1,city
				123 Main St,Salem
				,Knoxville
				""");
		String mappingId = createMapping(true);
		String definitionId = createActiveDefinition(mappingId, 10_000, "UAM", "SINGLE");
		createSource(definitionId, objectKey);
		String runId = createRun(definitionId);
		prepare(runId);
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/process-next-batch", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED_WITH_ERRORS"));
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/reconcile", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PASSED_WITH_WARNINGS"))
				.andExpect(jsonPath("$.reconciliation.count.details.internallyConsistent").value(true));
		mockMvc.perform(get("/api/v1/migration-runs/{runId}", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED_WITH_ERRORS"));
	}

	private String createMapping(boolean mandatoryStreet) throws Exception {
		MvcResult created = mockMvc.perform(post("/api/v1/mappings")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "phase8-%s",
								  "version": "1.0.0",
								  "sourceType": "FILE",
								  "targetType": "GENERIC"
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
								  "mandatory": %s,
								  "enabled": true
								}
								""".formatted(mandatoryStreet)))
				.andExpect(status().isCreated());
		mockMvc.perform(post("/api/v1/mappings/{id}/rules", mappingId)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "sequenceNo": 2,
								  "sourceField": "city",
								  "targetField": "city",
								  "transformationType": "DIRECT",
								  "mandatory": false,
								  "enabled": true
								}
								"""))
				.andExpect(status().isCreated());
		return mappingId;
	}

	private String createActiveDefinition(String mappingId, int batchSize, String targetType, String loadMode) throws Exception {
		String mappingJson = mappingId == null ? "" : ", \"mappingId\": \"" + mappingId + "\"";
		MvcResult created = mockMvc.perform(post("/api/v1/migrations")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "phase9-def-%s",
								  "version": "1.0.0",
								  "sourceType": "FILE",
								  "targetType": "%s",
								  "targetEntity": "address",
								  "loadMode": "%s",
								  "batchSize": %d
								  %s
								}
								""".formatted(System.nanoTime(), targetType, loadMode, batchSize, mappingJson)))
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

	private String readId(MvcResult result) throws Exception {
		return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id");
	}
}
