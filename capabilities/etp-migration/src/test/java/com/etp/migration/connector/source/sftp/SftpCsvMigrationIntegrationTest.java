package com.etp.migration.connector.source.sftp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.etp.migration.connector.source.SftpSourceException;
import com.etp.migration.connector.target.postgresql.PostgreSqlTargetClientConfig;
import com.etp.migration.entity.MigrationConfigStatus;
import com.etp.migration.entity.MigrationDefinition;
import com.etp.migration.identity.TargetIdentityLookup;
import com.etp.migration.processing.MigrationErrorCodes;
import com.etp.migration.repository.MigrationDefinitionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
class SftpCsvMigrationIntegrationTest {

	private static final String USER = "sftp-user";
	private static final String PASSWORD = UUID.randomUUID().toString();
	private static final String CREDENTIAL_REF = "it-sftp";

	private static final Path KNOWN_HOSTS = knownHostsPath();

	private static Path root;
	private static SshServer server;
	private static int port;

	@DynamicPropertySource
	static void sftpProperties(DynamicPropertyRegistry registry) {
		registry.add("etp.migration.source.sftp.known-hosts-file", KNOWN_HOSTS::toString);
		registry.add("etp.migration.source.sftp.connect-timeout", () -> "5s");
		registry.add("etp.migration.source.sftp.credentials." + CREDENTIAL_REF + ".username", () -> USER);
		registry.add("etp.migration.source.sftp.credentials." + CREDENTIAL_REF + ".password", () -> PASSWORD);
	}

	@BeforeAll
	static void startSftp() throws Exception {
		root = Files.createTempDirectory("sftp-source");
		Path inbound = root.resolve("inbound");
		Files.createDirectories(inbound);
		Files.writeString(inbound.resolve("rows.csv"), """
				location_id,location_name,address_id,address_name,address_location_id
				L001,Main,,,
				,,A001,One,L001
				""");
		server = SshServer.setUpDefaultServer();
		server.setPort(0);
		SimpleGeneratorHostKeyProvider hostKeys = new SimpleGeneratorHostKeyProvider(root.resolve("host.ser"));
		hostKeys.setAlgorithm("EC");
		hostKeys.setKeySize(256);
		server.setKeyPairProvider(hostKeys);
		server.setPasswordAuthenticator((username, password, session) -> USER.equals(username) && PASSWORD.equals(password));
		server.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
		server.setFileSystemFactory(new VirtualFileSystemFactory(root));
		server.start();
		port = server.getPort();
		PublicKey hostKey = server.getKeyPairProvider().loadKeys(null).iterator().next().getPublic();
		Files.writeString(KNOWN_HOSTS, "[127.0.0.1]:" + port + " " + PublicKeyEntry.toString(hostKey) + System.lineSeparator());
	}

	private static Path knownHostsPath() {
		try {
			return Files.createTempFile("sftp-it-known-hosts", "");
		} catch (java.io.IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	@AfterAll
	static void stopSftp() throws Exception {
		if (server != null) {
			server.stop();
		}
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private MigrationDefinitionRepository migrationDefinitionRepository;

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
	void remoteCsvReachesStagingAndClosesTheSession() throws Exception {
		String mappingId = createMapping();
		String definitionId = createDefinition(mappingId);
		MvcResult created = mockMvc.perform(post("/api/v1/migrations/{id}/sources", definitionId)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content(sourceJson()))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.configuration.provider").value("SFTP"))
				.andExpect(jsonPath("$.configuration.credentialRef").value(CREDENTIAL_REF))
				.andExpect(jsonPath("$.configuration.remotePath").value("/inbound/rows.csv"))
				.andReturn();
		assertThat(created.getResponse().getContentAsString()).doesNotContain(PASSWORD);

		String runId = createRun(definitionId);
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/prepare", runId).with(jwt()))
				.andExpect(status().isOk());
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/process-next-batch", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.successRecords").value(2));
		mockMvc.perform(get("/api/v1/migration-runs/{runId}", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"));

		List<Map<String, Object>> payloads = payloads(UUID.fromString(runId));
		assertThat(payloads).hasSize(2);
		assertThat(payloads).anySatisfy(payload -> {
			assertThat(payload.get("entity")).isEqualTo("Location");
			assertThat(payload.get("sourceIdentity")).isEqualTo("L001");
		});
		assertThat(payloads).anySatisfy(payload -> {
			assertThat(payload.get("entity")).isEqualTo("Address");
			assertThat(payload.get("sourceIdentity")).isEqualTo("A001");
		});
		assertThat(server.getActiveSessions()).isEmpty();
	}

	@Test
	void changedHostKeyStopsPrepareWithHostKeyCode() throws Exception {
		String trusted = Files.readString(KNOWN_HOSTS);
		KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
		generator.initialize(256);
		PublicKey impostor = generator.generateKeyPair().getPublic();
		String mappingId = createMapping();
		String definitionId = createDefinition(mappingId);
		mockMvc.perform(post("/api/v1/migrations/{id}/sources", definitionId)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content(sourceJson()))
				.andExpect(status().isCreated());
		String runId = createRun(definitionId);
		Files.writeString(KNOWN_HOSTS, "[127.0.0.1]:" + port + " " + PublicKeyEntry.toString(impostor) + System.lineSeparator());
		try {
			MvcResult rejected = mockMvc.perform(post("/api/v1/migration-runs/{runId}/prepare", runId).with(jwt()))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value(MigrationErrorCodes.SFTP_HOST_KEY_REJECTED))
					.andReturn();
			assertThat(rejected.getResponse().getContentAsString())
					.contains("SHA256:")
					.doesNotContain(PASSWORD)
					.doesNotContain(KNOWN_HOSTS.toString().replace("\\", "\\\\"));
		} finally {
			Files.writeString(KNOWN_HOSTS, trusted);
		}
		assertThat(server.getActiveSessions()).isEmpty();
	}

	@Test
	void authenticationFailureAndMissingFileCloseTheSession() {
		SftpCredentialProperties properties = new SftpCredentialProperties();
		properties.setKnownHostsFile(KNOWN_HOSTS);
		properties.setConnectTimeout(Duration.ofSeconds(5));
		SshdSftpRemoteClient client = new SshdSftpRemoteClient(properties);
		String wrongPassword = UUID.randomUUID().toString();

		assertThatThrownBy(() -> client.open(new SftpConnectionRequest(
				"127.0.0.1", port, USER, wrongPassword, "/inbound/rows.csv")))
				.isInstanceOf(SftpSourceException.class)
				.hasFieldOrPropertyWithValue("errorCode", MigrationErrorCodes.SFTP_AUTHENTICATION_FAILED)
				.hasMessageNotContaining(wrongPassword)
				.hasMessageNotContaining(PASSWORD);
		assertThatThrownBy(() -> client.open(new SftpConnectionRequest(
				"127.0.0.1", port, USER, PASSWORD, "/inbound/missing.csv")))
				.isInstanceOf(SftpSourceException.class)
				.hasFieldOrPropertyWithValue("errorCode", MigrationErrorCodes.SFTP_FILE_NOT_FOUND)
				.hasMessageNotContaining(PASSWORD);
		assertThat(server.getActiveSessions()).isEmpty();
	}

	@TestConfiguration
	static class IdentityLookupConfiguration {
		@Bean
		TargetIdentityLookup targetIdentityLookup() {
			return (entityName, sourceIdentities) -> {
				Map<String, String> resolved = new LinkedHashMap<>();
				for (String sourceIdentity : sourceIdentities) {
					resolved.put(sourceIdentity, "T" + sourceIdentity);
				}
				return resolved;
			};
		}
	}

	private String sourceJson() {
		return """
				{
				  "name": "sftp-source",
				  "sourceType": "FILE",
				  "configuration": {
				    "provider": "SFTP",
				    "fileFormat": "CSV",
				    "host": "127.0.0.1",
				    "port": %d,
				    "credentialRef": "%s",
				    "remotePath": "/inbound/rows.csv",
				    "hasHeader": true,
				    "delimiter": ","
				  }
				}
				""".formatted(port, CREDENTIAL_REF);
	}

	private String createMapping() throws Exception {
		MvcResult created = mockMvc.perform(post("/api/v1/mappings")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "sftp-entity-%s",
								  "version": "1.0.0",
								  "sourceType": "FILE",
								  "targetType": "POSTGRESQL",
								  "configuration": {
								    "modelType": "ENTITY_RELATIONSHIP",
								    "rootEntity": "Location",
								    "entities": [
								      {"name": "Location", "targetEntity": "staged_location", "sourceKey": "location_id", "targetKey": "id"},
								      {"name": "Address", "targetEntity": "staged_address", "sourceKey": "address_id", "targetKey": "id"}
								    ],
								    "relationships": [
								      {
								        "parent": "Location",
								        "child": "Address",
								        "cardinality": "ONE_TO_MANY",
								        "keys": [{"parentField": "location_id", "childField": "address_location_id"}]
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

	private String createDefinition(String mappingId) throws Exception {
		MvcResult created = mockMvc.perform(post("/api/v1/migrations")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "sftp-run-%s",
								  "version": "1.0.0",
								  "sourceType": "FILE",
								  "targetType": "POSTGRESQL",
								  "targetEntity": "staged_entity",
								  "loadMode": "BULK",
								  "batchSize": 10,
								  "mappingId": "%s",
								  "configuration": {
								    "target": {
								      "schema": "migration_staging",
								      "stagingTable": "entity_load",
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

	private String createRun(String definitionId) throws Exception {
		MvcResult created = mockMvc.perform(post("/api/v1/migrations/{id}/runs", definitionId)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isCreated())
				.andReturn();
		return readId(created);
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

	private static String readId(MvcResult result) throws Exception {
		return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id");
	}
}
