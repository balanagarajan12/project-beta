package com.etp.migration.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.etp.migration.entity.ArtifactType;
import com.etp.migration.entity.MigrationConfigStatus;
import com.etp.migration.entity.MigrationDefinition;
import com.etp.migration.repository.MigrationArtifactRepository;
import com.etp.migration.repository.MigrationBatchRepository;
import com.etp.migration.repository.MigrationDefinitionRepository;
import com.etp.migration.repository.MigrationRunRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
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

@SpringBootTest
@AutoConfigureMockMvc
class SourceInspectionIntegrationTest {

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
	private MigrationBatchRepository migrationBatchRepository;

	@Autowired
	private MigrationArtifactRepository migrationArtifactRepository;

	@Autowired
	private TransactionTemplate transactionTemplate;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	@Test
	void prepareInspectsCsvPersistsSnapshotAndCreatesBatches() throws Exception {
		String objectKey = "samples/addresses-" + System.nanoTime() + ".csv";
		writeCsv(objectKey, true, 25_001);
		String definitionId = createActiveDefinition(10_000);
		createSource(definitionId, objectKey);
		String runId = createRun(definitionId);

		mockMvc.perform(post("/api/v1/migration-runs/{runId}/prepare", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("QUEUED"))
				.andExpect(jsonPath("$.sourceRecordCount").value(25_001))
				.andExpect(jsonPath("$.batchSize").value(10_000))
				.andExpect(jsonPath("$.batchCount").value(3))
				.andExpect(jsonPath("$.statusMessage").value("SOURCE_INSPECTED"))
				.andExpect(jsonPath("$.sourceChecksum").isNotEmpty());

		mockMvc.perform(post("/api/v1/migration-runs/{runId}/prepare", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.batchCount").value(3));

		UUID runUuid = UUID.fromString(runId);
		assertThat(migrationBatchRepository.countByMigrationRun_Id(runUuid)).isEqualTo(3);
		assertThat(migrationArtifactRepository.countByMigrationRun_IdAndArtifactType(runUuid, ArtifactType.RAW_SOURCE))
				.isEqualTo(1);

		mockMvc.perform(get("/api/v1/migration-runs/{runId}/batches", runId)
						.param("page", "0")
						.param("size", "20")
						.with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(3));

		var batches = migrationBatchRepository.findByMigrationRun_IdOrderByBatchNumberAsc(runUuid);
		assertThat(batches.get(0).getSourceLocator().get("type")).isEqualTo("CSV_ROW_RANGE");
		assertThat(batches.get(0).getSourceLocator().get("objectKey")).isEqualTo(objectKey);
		assertThat(((Number) batches.get(0).getSourceLocator().get("startRecord")).longValue()).isEqualTo(1L);
		assertThat(((Number) batches.get(0).getSourceLocator().get("endRecord")).longValue()).isEqualTo(10_000L);
		assertThat(((Number) batches.get(2).getSourceLocator().get("startRecord")).longValue()).isEqualTo(20_001L);
		assertThat(((Number) batches.get(2).getSourceLocator().get("endRecord")).longValue()).isEqualTo(25_001L);

		var snapshot = migrationRunRepository.findById(runUuid).orElseThrow().getSourceSnapshot();
		assertThat(snapshot.get("statusMessage")).isEqualTo("SOURCE_INSPECTED");
		@SuppressWarnings("unchecked")
		var source = (java.util.Map<String, Object>) snapshot.get("source");
		assertThat(((Number) source.get("recordCount")).longValue()).isEqualTo(25_001L);
		assertThat(source.get("format")).isEqualTo("CSV");
	}

	@Test
	void prepareExactAndOverflowBatchCounts() throws Exception {
		assertBatchCount(30_000, 3);
		assertBatchCount(30_001, 4);
	}

	@Test
	void prepareZeroRecordsCreatesNoBatches() throws Exception {
		String objectKey = "samples/empty-" + System.nanoTime() + ".csv";
		writeCsv(objectKey, true, 0);
		String definitionId = createActiveDefinition(10_000);
		createSource(definitionId, objectKey);
		String runId = createRun(definitionId);

		mockMvc.perform(post("/api/v1/migration-runs/{runId}/prepare", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sourceRecordCount").value(0))
				.andExpect(jsonPath("$.batchCount").value(0))
				.andExpect(jsonPath("$.status").value("QUEUED"));

		assertThat(migrationBatchRepository.countByMigrationRun_Id(UUID.fromString(runId))).isZero();
	}

	@Test
	void prepareRejectedWhenRunIsNotQueued() throws Exception {
		String objectKey = "samples/cancel-" + System.nanoTime() + ".csv";
		writeCsv(objectKey, true, 1);
		String definitionId = createActiveDefinition(10_000);
		createSource(definitionId, objectKey);
		String runId = createRun(definitionId);
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/cancel", runId).with(jwt()))
				.andExpect(status().isOk());
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/prepare", runId).with(jwt()))
				.andExpect(status().isConflict());
	}

	@Test
	void prepareMissingFileReturnsNotFound() throws Exception {
		String definitionId = createActiveDefinition(10_000);
		createSource(definitionId, "samples/does-not-exist.csv");
		String runId = createRun(definitionId);
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/prepare", runId).with(jwt()))
				.andExpect(status().isNotFound());
	}

	private void assertBatchCount(int records, int expectedBatches) throws Exception {
		String objectKey = "samples/count-" + records + "-" + System.nanoTime() + ".csv";
		writeCsv(objectKey, true, records);
		String definitionId = createActiveDefinition(10_000);
		createSource(definitionId, objectKey);
		String runId = createRun(definitionId);
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/prepare", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.batchCount").value(expectedBatches))
				.andExpect(jsonPath("$.sourceRecordCount").value(records));
	}

	@Test
	void prepareInspectsFileSystemCsvSource() throws Exception {
		String objectKey = "demo/addresses-" + System.nanoTime() + ".csv";
		writeCsv(objectKey, true, 3);
		String definitionId = createActiveDefinition(10_000);
		createSource(definitionId, objectKey, "FILE_SYSTEM");
		String runId = createRun(definitionId);

		mockMvc.perform(post("/api/v1/migration-runs/{runId}/prepare", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("QUEUED"))
				.andExpect(jsonPath("$.sourceRecordCount").value(3))
				.andExpect(jsonPath("$.batchCount").value(1));
	}

	private void writeCsv(String objectKey, boolean header, int records) throws Exception {
		Path path = storageRoot.resolve(objectKey);
		Files.createDirectories(path.getParent());
		try (var writer = Files.newBufferedWriter(path)) {
			if (header) {
				writer.write("addr1,city\n");
			}
			for (int i = 1; i <= records; i++) {
				writer.write("a" + i + ",c" + i + "\n");
			}
		}
	}

	private String createActiveDefinition(int batchSize) throws Exception {
		MvcResult created = mockMvc.perform(post("/api/v1/migrations")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "phase7-%s",
								  "version": "1.0.0",
								  "sourceType": "FILE",
								  "targetType": "UAM",
								  "batchSize": %d
								}
								""".formatted(System.nanoTime(), batchSize)))
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
		createSource(definitionId, objectKey, "FILE");
	}

	private void createSource(String definitionId, String objectKey, String sourceType) throws Exception {
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
								    "delimiter": ",",
								    "hasHeader": true,
								    "encoding": "UTF-8",
								    "quoteCharacter": "\\""
								  }
								}
								""".formatted(sourceType, objectKey)))
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

	private String readId(MvcResult result) throws Exception {
		return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id");
	}
}
