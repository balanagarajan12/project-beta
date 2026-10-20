package com.etp.migration.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.etp.migration.connector.target.postgresql.PostgreSqlTargetClientConfig;
import com.etp.migration.entity.MigrationConfigStatus;
import com.etp.migration.entity.MigrationDefinition;
import com.etp.migration.entity.MigrationMapping;
import com.etp.migration.repository.MigrationDefinitionRepository;
import com.etp.migration.repository.MigrationMappingRepository;
import com.etp.migration.repository.MigrationMappingRuleRepository;
import com.etp.migration.repository.MigrationPromotionRepository;
import com.etp.migration.repository.MigrationReconciliationRepository;
import com.etp.migration.repository.MigrationRunRepository;
import com.etp.migration.repository.MigrationSourceRepository;
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

@SpringBootTest
@AutoConfigureMockMvc
class MigrationActivationIntegrationTest {

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
	private MigrationMappingRepository mappingRepository;
	@Autowired
	private MigrationMappingRuleRepository ruleRepository;
	@Autowired
	private MigrationSourceRepository sourceRepository;
	@Autowired
	private MigrationRunRepository runRepository;
	@Autowired
	private MigrationPromotionRepository promotionRepository;
	@Autowired
	private MigrationReconciliationRepository reconciliationRepository;
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
	void activatePromotesDraftToActiveWithoutExecuting() throws Exception {
		long runs = runRepository.count();
		long promotions = promotionRepository.count();
		long reconciliations = reconciliationRepository.count();
		Created created = completeDraft();

		mockMvc.perform(post("/api/v1/migrations/{id}/activate", created.definitionId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.id").value(created.definitionId));

		mockMvc.perform(post("/api/v1/migrations/{id}/activate", created.definitionId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACTIVE"));

		MigrationDefinition definition = definitionRepository.findById(UUID.fromString(created.definitionId)).orElseThrow();
		assertThat(definition.getStatus()).isEqualTo(MigrationConfigStatus.ACTIVE);
		MigrationMapping mapping = mappingRepository.findById(UUID.fromString(created.mappingId)).orElseThrow();
		assertThat(mapping.getName()).isEqualTo(created.mappingName);
		assertThat(ruleRepository.findByMapping_IdAndEnabledTrueOrderBySequenceNoAsc(mapping.getId())).hasSize(1);
		assertThat(sourceRepository.findById(UUID.fromString(created.sourceId)).orElseThrow().isEnabled()).isTrue();
		assertThat(runRepository.count()).isEqualTo(runs);
		assertThat(promotionRepository.count()).isEqualTo(promotions);
		assertThat(reconciliationRepository.count()).isEqualTo(reconciliations);

		mockMvc.perform(post("/api/v1/migrations/{id}/execute", created.definitionId)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("COMPLETED"));
	}

	@Test
	void draftDefinitionStillCannotExecute() throws Exception {
		Created created = completeDraft();

		mockMvc.perform(post("/api/v1/migrations/{id}/execute", created.definitionId)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isConflict());
		assertThat(definitionRepository.findById(UUID.fromString(created.definitionId)).orElseThrow().getStatus())
				.isEqualTo(MigrationConfigStatus.DRAFT);
	}

	@Test
	void activateAcceptsFileSystemCsvSourceWithoutReadingTheFile() throws Exception {
		long runs = runRepository.count();
		String mappingId = createMappingWithRule();
		MvcResult definition = mockMvc.perform(post("/api/v1/migrations")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "phase14c-filesystem-%s",
								  "version": "1.0.0",
								  "sourceType": "FILE_SYSTEM",
								  "targetType": "POSTGRESQL",
								  "targetEntity": "address",
								  "mappingId": "%s",
								  "configuration": {
								    "target": {
								      "stagingSchema": "migration_staging",
								      "stagingTable": "stage_table"
								    }
								  }
								}
								""".formatted(System.nanoTime(), mappingId)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("DRAFT"))
				.andReturn();
		String definitionId = read(definition, "$.id");
		mockMvc.perform(post("/api/v1/migrations/{id}/sources", definitionId)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "csv-source",
								  "sourceType": "FILE_SYSTEM",
								  "configuration": {
								    "hasHeader": true,
								    "provider": "FILE_SYSTEM",
								    "objectKey": "demo/addresses.csv",
								    "fileFormat": "CSV"
								  }
								}
								"""))
				.andExpect(status().isCreated());

		mockMvc.perform(post("/api/v1/migrations/{id}/activate", definitionId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACTIVE"));

		assertThat(definitionRepository.findById(UUID.fromString(definitionId)).orElseThrow().getStatus())
				.isEqualTo(MigrationConfigStatus.ACTIVE);
		assertThat(runRepository.count()).isEqualTo(runs);
		assertThat(Files.exists(storageRoot.resolve("demo/addresses.csv"))).isFalse();
	}

	@Test
	void activateIncompleteDefinitionReturnsBadRequest() throws Exception {
		MvcResult created = mockMvc.perform(post("/api/v1/migrations")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "phase14c-incomplete-%s",
								  "version": "1.0.0",
								  "sourceType": "FILE",
								  "targetType": "POSTGRESQL",
								  "configuration": {
								    "target": {
								      "stagingSchema": "migration_staging",
								      "stagingTable": "stage_table"
								    }
								  }
								}
								""".formatted(System.nanoTime())))
				.andExpect(status().isCreated())
				.andReturn();
		String id = read(created, "$.id");

		mockMvc.perform(post("/api/v1/migrations/{id}/activate", id).with(jwt()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Migration mapping is not configured"));
	}

	@Test
	void activateInvalidSourceConfigurationReturnsBadRequest() throws Exception {
		String mappingId = createMappingWithRule();
		MvcResult definition = mockMvc.perform(post("/api/v1/migrations")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "phase14c-bad-source-%s",
								  "version": "1.0.0",
								  "sourceType": "FILE",
								  "targetType": "POSTGRESQL",
								  "targetEntity": "address",
								  "mappingId": "%s",
								  "configuration": {
								    "target": {
								      "stagingSchema": "migration_staging",
								      "stagingTable": "stage_table"
								    }
								  }
								}
								""".formatted(System.nanoTime(), mappingId)))
				.andExpect(status().isCreated())
				.andReturn();
		String definitionId = read(definition, "$.id");
		mockMvc.perform(post("/api/v1/migrations/{id}/sources", definitionId)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "empty-source",
								  "sourceType": "FILE",
								  "configuration": {}
								}
								"""))
				.andExpect(status().isCreated());

		mockMvc.perform(post("/api/v1/migrations/{id}/activate", definitionId).with(jwt()))
				.andExpect(status().isBadRequest());
	}

	@Test
	void activateUnknownDefinitionReturnsNotFound() throws Exception {
		mockMvc.perform(post("/api/v1/migrations/{id}/activate", "99999999-9999-9999-9999-999999999999")
						.with(jwt()))
				.andExpect(status().isNotFound());
	}

	@Test
	void unauthenticatedActivateIsRejected() throws Exception {
		mockMvc.perform(post("/api/v1/migrations/{id}/activate", UUID.randomUUID()))
				.andExpect(status().isUnauthorized());
	}

	private Created completeDraft() throws Exception {
		String mappingId = createMappingWithRule();
		String mappingName = mappingRepository.findById(UUID.fromString(mappingId)).orElseThrow().getName();
		MvcResult definition = mockMvc.perform(post("/api/v1/migrations")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "phase14c-def-%s",
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
				.andExpect(jsonPath("$.status").value("DRAFT"))
				.andReturn();
		String definitionId = read(definition, "$.id");
		String objectKey = "samples/phase14c-" + System.nanoTime() + ".csv";
		Path path = storageRoot.resolve(objectKey);
		Files.createDirectories(path.getParent());
		Files.writeString(path, """
				addr1,city
				123 Main St,Salem
				""");
		MvcResult source = mockMvc.perform(post("/api/v1/migrations/{id}/sources", definitionId)
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
				.andExpect(status().isCreated())
				.andReturn();
		return new Created(definitionId, mappingId, mappingName, read(source, "$.id"));
	}

	private String createMappingWithRule() throws Exception {
		MvcResult mapping = mockMvc.perform(post("/api/v1/mappings")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "phase14c-map-%s",
								  "version": "1.0.0",
								  "sourceType": "FILE",
								  "targetType": "POSTGRESQL"
								}
								""".formatted(System.nanoTime())))
				.andExpect(status().isCreated())
				.andReturn();
		String mappingId = read(mapping, "$.id");
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

	private static String read(MvcResult result, String path) throws Exception {
		return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
	}

	private record Created(String definitionId, String mappingId, String mappingName, String sourceId) {
	}
}
