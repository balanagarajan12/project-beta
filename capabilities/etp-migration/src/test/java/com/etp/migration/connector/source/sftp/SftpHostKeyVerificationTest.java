package com.etp.migration.connector.source.sftp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.etp.migration.connector.source.SftpSourceException;
import com.etp.migration.entity.MigrationRun;
import com.etp.migration.entity.MigrationSource;
import com.etp.migration.processing.MigrationErrorCodes;
import com.etp.migration.processing.SourceRecord;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SftpHostKeyVerificationTest {

	private static final String HOST = "127.0.0.1";
	private static final String USER = "sftp-user";
	private static final String PASSWORD = UUID.randomUUID().toString();
	private static final String CREDENTIAL_REF = "partner";
	private static final String CSV = "code,label\nA1,North\nB2,South\n";

	private static Path root;
	private static SshServer server;
	private static int port;
	private static PublicKey serverKey;
	private static PublicKey otherKey;

	@TempDir
	Path tempDir;

	@BeforeAll
	static void startServer() throws Exception {
		root = Files.createTempDirectory("sftp-host-key");
		Files.createDirectories(root.resolve("inbound"));
		Files.writeString(root.resolve("inbound/rows.csv"), CSV);
		SimpleGeneratorHostKeyProvider keys = new SimpleGeneratorHostKeyProvider(root.resolve("host.ser"));
		keys.setAlgorithm("EC");
		keys.setKeySize(256);
		server = SshServer.setUpDefaultServer();
		server.setPort(0);
		server.setKeyPairProvider(keys);
		server.setPasswordAuthenticator((username, password, session) -> USER.equals(username) && PASSWORD.equals(password));
		server.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
		server.setFileSystemFactory(new VirtualFileSystemFactory(root));
		server.start();
		port = server.getPort();
		serverKey = keys.loadKeys(null).iterator().next().getPublic();
		KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
		generator.initialize(256);
		otherKey = generator.generateKeyPair().getPublic();
	}

	@AfterAll
	static void stopServer() throws Exception {
		if (server != null) {
			server.stop();
		}
	}

	@AfterEach
	void sessionsAreClosed() throws InterruptedException {
		for (int i = 0; i < 50 && !server.getActiveSessions().isEmpty(); i++) {
			Thread.sleep(20);
		}
		assertThat(server.getActiveSessions()).isEmpty();
	}

	@Test
	void trustedKnownHostsEntryConnects() throws Exception {
		SftpCredentialProperties properties = properties();
		properties.setKnownHostsFile(knownHosts("[" + HOST + "]:" + port + " " + PublicKeyEntry.toString(serverKey)));

		assertThat(read(properties)).isEqualTo(CSV);
	}

	@Test
	void unknownHostIsRejectedByDefault() throws Exception {
		SftpCredentialProperties properties = properties();
		Path file = knownHosts("[other.example]:22 " + PublicKeyEntry.toString(otherKey));
		properties.setKnownHostsFile(file);
		String before = Files.readString(file);

		assertThatThrownBy(() -> read(properties))
				.isInstanceOf(SftpSourceException.class)
				.hasFieldOrPropertyWithValue("errorCode", MigrationErrorCodes.SFTP_HOST_KEY_REJECTED)
				.hasMessageContaining("is not trusted")
				.hasMessageContaining(SftpHostKeyVerifier.fingerprint(serverKey))
				.hasMessageContaining("known-hosts-file")
				.hasMessageNotContaining(PASSWORD);
		assertThat(Files.readString(file)).isEqualTo(before);
	}

	@Test
	void changedHostKeyIsRejected() throws Exception {
		SftpCredentialProperties properties = properties();
		Path file = knownHosts("[" + HOST + "]:" + port + " " + PublicKeyEntry.toString(otherKey));
		properties.setKnownHostsFile(file);
		String before = Files.readString(file);

		assertThatThrownBy(() -> read(properties))
				.isInstanceOf(SftpSourceException.class)
				.hasFieldOrPropertyWithValue("errorCode", MigrationErrorCodes.SFTP_HOST_KEY_REJECTED)
				.hasMessageContaining("changed")
				.hasMessageContaining(SftpHostKeyVerifier.fingerprint(serverKey))
				.hasMessageNotContaining(PASSWORD);
		assertThat(Files.readString(file)).isEqualTo(before);
	}

	@Test
	void acceptUnknownNeverOverridesAChangedKnownHostsEntry() throws Exception {
		SftpCredentialProperties properties = properties();
		properties.setAcceptUnknownHostKeys(true);
		properties.setKnownHostsFile(knownHosts("[" + HOST + "]:" + port + " " + PublicKeyEntry.toString(otherKey)));

		assertThatThrownBy(() -> read(properties))
				.isInstanceOf(SftpSourceException.class)
				.hasFieldOrPropertyWithValue("errorCode", MigrationErrorCodes.SFTP_HOST_KEY_REJECTED)
				.hasMessageContaining("changed");
	}

	@Test
	void acceptUnknownAdmitsAHostMissingFromKnownHostsWithoutWritingTheFile() throws Exception {
		SftpCredentialProperties properties = properties();
		properties.setAcceptUnknownHostKeys(true);
		Path file = knownHosts("[other.example]:22 " + PublicKeyEntry.toString(otherKey));
		properties.setKnownHostsFile(file);
		String before = Files.readString(file);

		assertThat(read(properties)).isEqualTo(CSV);
		assertThat(Files.readString(file)).isEqualTo(before);
	}

	@Test
	void acceptUnknownWithoutAnyTrustSourceConnects() throws Exception {
		SftpCredentialProperties properties = properties();
		properties.setAcceptUnknownHostKeys(true);

		assertThat(read(properties)).isEqualTo(CSV);
	}

	@Test
	void pinnedFingerprintMatchConnects() throws Exception {
		SftpCredentialProperties properties = properties();
		properties.getCredentials().get(CREDENTIAL_REF).setHostKeyFingerprints(List.of(
				SftpHostKeyVerifier.fingerprint(otherKey),
				SftpHostKeyVerifier.fingerprint(serverKey) + "="));

		assertThat(read(properties)).isEqualTo(CSV);
	}

	@Test
	void pinnedFingerprintMismatchIsRejectedEvenWhenKnownHostsAndAcceptUnknownWouldAllowIt() throws Exception {
		SftpCredentialProperties properties = properties();
		properties.setAcceptUnknownHostKeys(true);
		properties.setKnownHostsFile(knownHosts("[" + HOST + "]:" + port + " " + PublicKeyEntry.toString(serverKey)));
		properties.getCredentials().get(CREDENTIAL_REF).setHostKeyFingerprints(List.of(
				SftpHostKeyVerifier.fingerprint(otherKey)));

		assertThatThrownBy(() -> read(properties))
				.isInstanceOf(SftpSourceException.class)
				.hasFieldOrPropertyWithValue("errorCode", MigrationErrorCodes.SFTP_HOST_KEY_REJECTED)
				.hasMessageContaining("does not match")
				.hasMessageContaining("credentials." + CREDENTIAL_REF + ".host-key-fingerprints")
				.hasMessageContaining(SftpHostKeyVerifier.fingerprint(serverKey));
	}

	@Test
	void missingTrustConfigurationFailsBeforeConnecting() {
		SftpCredentialProperties properties = properties();
		int acceptedBefore = server.getActiveSessions().size();

		assertThatThrownBy(() -> read(properties))
				.isInstanceOf(SftpSourceException.class)
				.hasFieldOrPropertyWithValue("errorCode", MigrationErrorCodes.SFTP_HOST_KEY_CONFIGURATION_INVALID)
				.hasMessageContaining("known-hosts-file")
				.hasMessageContaining("credentials." + CREDENTIAL_REF + ".host-key-fingerprints")
				.hasMessageContaining("accept-unknown-host-keys")
				.hasMessageNotContaining(PASSWORD);
		assertThat(server.getActiveSessions()).hasSize(acceptedBefore);
	}

	@Test
	void missingKnownHostsFileIsAConfigurationErrorThatHidesThePath() {
		SftpCredentialProperties properties = properties();
		Path missing = tempDir.resolve("absent-known-hosts");
		properties.setKnownHostsFile(missing);

		assertThatThrownBy(() -> read(properties))
				.isInstanceOf(SftpSourceException.class)
				.hasFieldOrPropertyWithValue("errorCode", MigrationErrorCodes.SFTP_HOST_KEY_CONFIGURATION_INVALID)
				.hasMessageContaining("missing or unreadable")
				.hasMessageNotContaining(missing.toString());
	}

	@Test
	void unparseableKnownHostsFileIsAConfigurationError() throws Exception {
		SftpCredentialProperties properties = properties();
		properties.setKnownHostsFile(knownHosts("[" + HOST + "]:" + port + " ssh-rsa not-base64!!"));

		assertThatThrownBy(() -> read(properties))
				.isInstanceOf(SftpSourceException.class)
				.hasFieldOrPropertyWithValue("errorCode", MigrationErrorCodes.SFTP_HOST_KEY_CONFIGURATION_INVALID)
				.hasMessageContaining("could not be parsed");
	}

	@Test
	void malformedPinnedFingerprintIsAConfigurationErrorThatDoesNotEchoTheValue() {
		SftpCredentialProperties properties = properties();
		String malformed = "MD5:" + UUID.randomUUID();
		properties.getCredentials().get(CREDENTIAL_REF).setHostKeyFingerprints(List.of(malformed));

		assertThatThrownBy(() -> read(properties))
				.isInstanceOf(SftpSourceException.class)
				.hasFieldOrPropertyWithValue("errorCode", MigrationErrorCodes.SFTP_HOST_KEY_CONFIGURATION_INVALID)
				.hasMessageContaining("SHA256:<base64>")
				.hasMessageNotContaining(malformed);
	}

	@Test
	void trustedHostStreamsCsvRecordsThroughTheConnector() throws Exception {
		SftpCredentialProperties properties = properties();
		properties.getCredentials().get(CREDENTIAL_REF).setHostKeyFingerprints(List.of(
				SftpHostKeyVerifier.fingerprint(serverKey)));
		SftpCsvSourceConnector connector = new SftpCsvSourceConnector(
				new SshdSftpRemoteClient(properties),
				new PropertySftpCredentialResolver(properties));
		Map<String, Object> configuration = new LinkedHashMap<>();
		configuration.put("provider", "SFTP");
		configuration.put("fileFormat", "CSV");
		configuration.put("host", HOST);
		configuration.put("port", port);
		configuration.put("credentialRef", CREDENTIAL_REF);
		configuration.put("remotePath", "/inbound/rows.csv");
		configuration.put("hasHeader", true);
		MigrationSource source = new MigrationSource();
		source.setSourceType("FILE");
		source.setConfiguration(configuration);

		assertThat(connector.inspect(source, new MigrationRun()).recordCount()).isEqualTo(2L);
		List<SourceRecord> records;
		try (var stream = connector.readAll(SftpCsvSourceConfiguration.from(configuration))) {
			records = stream.toList();
		}
		assertThat(records).extracting(record -> record.fields().get("code")).containsExactly("A1", "B2");
	}

	private static SftpCredentialProperties properties() {
		SftpCredentialProperties properties = new SftpCredentialProperties();
		properties.setConnectTimeout(Duration.ofSeconds(5));
		SftpCredentialProperties.Credential credential = new SftpCredentialProperties.Credential();
		credential.setUsername(USER);
		credential.setPassword(PASSWORD);
		properties.getCredentials().put(CREDENTIAL_REF, credential);
		return properties;
	}

	private Path knownHosts(String line) throws Exception {
		Path file = Files.createTempFile(tempDir, "known_hosts", "");
		Files.writeString(file, line + System.lineSeparator());
		return file;
	}

	private static String read(SftpCredentialProperties properties) throws Exception {
		SftpRemoteFile remote = new SshdSftpRemoteClient(properties).open(new SftpConnectionRequest(
				HOST, port, USER, PASSWORD, "/inbound/rows.csv", CREDENTIAL_REF));
		try (InputStream input = remote.stream()) {
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		} finally {
			remote.close();
		}
	}
}
