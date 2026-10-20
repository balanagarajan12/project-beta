package com.etp.migration.connector.source.sftp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.etp.migration.connector.source.InvalidSourceConfigurationException;
import com.etp.migration.connector.source.SftpSourceException;
import com.etp.migration.connector.source.SourceInspection;
import com.etp.migration.connector.source.csv.CsvSourceConnector;
import com.etp.migration.connector.storage.DefaultArtifactStorage;
import com.etp.migration.connector.storage.FileSystemArtifactStorage;
import com.etp.migration.entity.MigrationRun;
import com.etp.migration.entity.MigrationSource;
import com.etp.migration.processing.MigrationErrorCodes;
import com.etp.migration.processing.SourceRecord;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SftpCsvSourceConnectorTest {

	@TempDir
	Path tempDir;

	@Test
	void configurationRequiresRemoteConnectionFields() {
		assertThatThrownBy(() -> SftpCsvSourceConfiguration.from(Map.of(
				"provider", "SFTP",
				"fileFormat", "CSV")))
				.isInstanceOf(InvalidSourceConfigurationException.class)
				.hasMessageContaining("host");
	}

	@Test
	void configurationRejectsInlineSecrets() {
		String secret = "credential-must-stay-out-" + UUID.randomUUID();
		Map<String, Object> configuration = validConfiguration();
		configuration.put("password", secret);

		assertThatThrownBy(() -> SftpCsvSourceConfiguration.from(configuration))
				.isInstanceOf(InvalidSourceConfigurationException.class)
				.hasMessageContaining("credentialRef")
				.hasMessageNotContaining(secret);
	}

	@Test
	void readsTheConfiguredRemotePathWithHeaderAndDelimiter() {
		RecordingClient client = new RecordingClient("code;label\nA1;North\n");
		SftpCsvSourceConnector connector = connector(client);
		Map<String, Object> configuration = validConfiguration();
		configuration.put("delimiter", ";");
		configuration.put("remotePath", "/inbound/rows.csv");

		SourceInspection inspection = connector.inspect(source(configuration), new MigrationRun());
		List<SourceRecord> records;
		try (var stream = connector.readAll(SftpCsvSourceConfiguration.from(configuration))) {
			records = stream.toList();
		}

		assertThat(client.last().remotePath()).isEqualTo("/inbound/rows.csv");
		assertThat(inspection.metadata()).containsEntry("remotePath", "/inbound/rows.csv");
		assertThat(inspection.metadata()).containsEntry("hasHeader", true);
		assertThat(inspection.columns()).extracting("name").containsExactly("code", "label");
		assertThat(inspection.recordCount()).isEqualTo(1L);
		assertThat(records).hasSize(1);
		assertThat(records.get(0).fields()).containsEntry("code", "A1").containsEntry("label", "North");
		assertThat(client.closed()).isTrue();
	}

	@Test
	void headerlessRowsUseSyntheticColumns() {
		RecordingClient client = new RecordingClient("a,b\nc,d\n");
		Map<String, Object> configuration = validConfiguration();
		configuration.put("hasHeader", false);
		SftpCsvSourceConnector connector = connector(client);

		SourceInspection inspection = connector.inspect(source(configuration), new MigrationRun());

		assertThat(inspection.recordCount()).isEqualTo(2L);
		assertThat(inspection.columns()).extracting("name").containsExactly("column_1", "column_2");
		assertThat(client.closed()).isTrue();
	}

	@Test
	void connectionAuthenticationAndMissingFileFailuresKeepSafeMessages() {
		String secret = UUID.randomUUID().toString();
		SftpCsvSourceConnector connector = connector(new RecordingClient(new SftpSourceException(
				MigrationErrorCodes.SFTP_CONNECTION_FAILED,
				"SFTP connection failed")));
		assertThatThrownBy(() -> connector.readAll(SftpCsvSourceConfiguration.from(validConfiguration())).close())
				.isInstanceOf(SftpSourceException.class)
				.hasFieldOrPropertyWithValue("errorCode", MigrationErrorCodes.SFTP_CONNECTION_FAILED)
				.hasMessage("SFTP connection failed")
				.hasMessageNotContaining(secret);

		RecordingClient authentication = new RecordingClient(new SftpSourceException(
				MigrationErrorCodes.SFTP_AUTHENTICATION_FAILED,
				"SFTP authentication failed"));
		assertThatThrownBy(() -> connector(authentication).inspect(source(validConfiguration()), new MigrationRun()))
				.isInstanceOf(SftpSourceException.class)
				.hasFieldOrPropertyWithValue("errorCode", MigrationErrorCodes.SFTP_AUTHENTICATION_FAILED);

		RecordingClient missing = new RecordingClient(new SftpSourceException(
				MigrationErrorCodes.SFTP_FILE_NOT_FOUND,
				"SFTP file was not found"));
		assertThatThrownBy(() -> connector(missing).inspect(source(validConfiguration()), new MigrationRun()))
				.isInstanceOf(SftpSourceException.class)
				.hasFieldOrPropertyWithValue("errorCode", MigrationErrorCodes.SFTP_FILE_NOT_FOUND);
	}

	@Test
	void protocolFailuresMapToSourceCodes() {
		assertThat(SftpSourceFailures.translate(new java.net.ConnectException("Connection refused")))
				.isInstanceOf(SftpSourceException.class)
				.hasFieldOrPropertyWithValue("errorCode", MigrationErrorCodes.SFTP_CONNECTION_FAILED)
				.hasMessage("SFTP connection failed");
		assertThat(SftpSourceFailures.translate(new org.apache.sshd.common.SshException("No more authentication methods available")))
				.hasFieldOrPropertyWithValue("errorCode", MigrationErrorCodes.SFTP_AUTHENTICATION_FAILED);
		assertThat(SftpSourceFailures.translate(new SftpException(SftpConstants.SSH_FX_NO_SUCH_FILE, "missing")))
				.hasFieldOrPropertyWithValue("errorCode", MigrationErrorCodes.SFTP_FILE_NOT_FOUND)
				.hasMessageNotContaining("missing");
		assertThat(SftpSourceFailures.translate(new SftpException(SftpConstants.SSH_FX_PERMISSION_DENIED, "denied")))
				.hasFieldOrPropertyWithValue("errorCode", MigrationErrorCodes.SFTP_PERMISSION_DENIED);
		assertThat(SftpSourceFailures.translate(new IOException("socket reset")))
				.hasFieldOrPropertyWithValue("errorCode", MigrationErrorCodes.SFTP_READ_FAILED)
				.hasMessage("SFTP read failed");
		assertThat(SftpSourceFailures.translate(new IllegalArgumentException("bad quote")))
				.hasFieldOrPropertyWithValue("errorCode", MigrationErrorCodes.CSV_PARSE_FAILED);
	}

	@Test
	void duplicateRemoteHeaderIsReportedAsSourceProblemAndClosesTheFile() {
		RecordingClient inspected = new RecordingClient("author_id,label,author_id\nA1,North,B2\n");
		assertThatThrownBy(() -> connector(inspected).inspect(source(validConfiguration()), new MigrationRun()))
				.isInstanceOf(InvalidSourceConfigurationException.class)
				.hasMessageContaining("duplicate column name 'author_id'");
		assertThat(inspected.closed()).isTrue();

		RecordingClient read = new RecordingClient("author_id,label,author_id\nA1,North,B2\n");
		assertThatThrownBy(() -> connector(read).readAll(SftpCsvSourceConfiguration.from(validConfiguration())).close())
				.isInstanceOf(InvalidSourceConfigurationException.class)
				.hasMessageContaining("duplicate column name 'author_id'");
		assertThat(read.closed()).isTrue();
	}

	@Test
	void remoteUtf8BomIsNotPartOfTheFirstHeader() {
		RecordingClient client = new RecordingClient("\uFEFFcode,label\nA1,North\n");
		SourceInspection inspection = connector(client).inspect(source(validConfiguration()), new MigrationRun());

		assertThat(inspection.columns()).extracting("name").containsExactly("code", "label");
	}

	@Test
	void readFailureClosesTheRemoteFile() {
		RecordingClient client = new RecordingClient("code,label\nA1,North\n");
		client.readFailure = new IOException("socket reset");
		SftpCsvSourceConnector connector = connector(client);

		assertThatThrownBy(() -> {
			try (var stream = connector.readAll(SftpCsvSourceConfiguration.from(validConfiguration()))) {
				stream.toList();
			}
		}).isInstanceOf(SftpSourceException.class)
				.hasFieldOrPropertyWithValue("errorCode", MigrationErrorCodes.SFTP_READ_FAILED)
				.hasMessage("SFTP read failed");
		assertThat(client.closed()).isTrue();
	}

	@Test
	void closedPortReportsConnectionFailure() {
		SftpCredentialProperties properties = new SftpCredentialProperties();
		properties.setAcceptUnknownHostKeys(true);
		properties.setConnectTimeout(Duration.ofSeconds(2));
		SshdSftpRemoteClient client = new SshdSftpRemoteClient(properties);
		String secret = UUID.randomUUID().toString();

		assertThatThrownBy(() -> client.open(new SftpConnectionRequest("127.0.0.1", 1, "sftp-user", secret, "/inbound/rows.csv")))
				.isInstanceOf(SftpSourceException.class)
				.hasFieldOrPropertyWithValue("errorCode", MigrationErrorCodes.SFTP_CONNECTION_FAILED)
				.hasMessage("SFTP connection failed")
				.hasMessageNotContaining(secret);
	}

	@Test
	void fileSystemCsvBehaviorRemainsUnchanged() throws Exception {
		FileSystemArtifactStorage fileSystem = new FileSystemArtifactStorage(tempDir);
		fileSystem.initializeRoot();
		Path file = tempDir.resolve("rows.csv");
		Files.writeString(file, "code,label\nA1,North\n");
		CsvSourceConnector connector = new CsvSourceConnector(new DefaultArtifactStorage(fileSystem));
		MigrationSource source = source(Map.of(
				"provider", "FILE_SYSTEM",
				"objectKey", "rows.csv",
				"fileFormat", "CSV",
				"hasHeader", true));

		SourceInspection inspection = connector.inspect(source, new MigrationRun());

		assertThat(inspection.recordCount()).isEqualTo(1L);
		assertThat(inspection.columns()).extracting("name").containsExactly("code", "label");
	}

	private static SftpCsvSourceConnector connector(RecordingClient client) {
		return new SftpCsvSourceConnector(client, ref -> new SftpCredential("sftp-user", UUID.randomUUID().toString()));
	}

	private static MigrationSource source(Map<String, Object> configuration) {
		MigrationSource source = new MigrationSource();
		source.setSourceType("FILE");
		source.setConfiguration(configuration);
		return source;
	}

	private static Map<String, Object> validConfiguration() {
		Map<String, Object> configuration = new LinkedHashMap<>();
		configuration.put("provider", "SFTP");
		configuration.put("fileFormat", "CSV");
		configuration.put("host", "sftp.example");
		configuration.put("port", 22);
		configuration.put("credentialRef", "local-sftp");
		configuration.put("remotePath", "/inbound/rows.csv");
		configuration.put("hasHeader", true);
		configuration.put("delimiter", ",");
		return configuration;
	}

	private static final class RecordingClient implements SftpRemoteClient {
		private final String body;
		private final RuntimeException failure;
		private final AtomicBoolean closedFlag = new AtomicBoolean();
		private final AtomicReference<SftpConnectionRequest> request = new AtomicReference<>();
		private IOException readFailure;

		private RecordingClient(String body) {
			this.body = body;
			this.failure = null;
		}

		private RecordingClient(RuntimeException failure) {
			this.body = "";
			this.failure = failure;
		}

		@Override
		public SftpRemoteFile open(SftpConnectionRequest request) {
			this.request.set(request);
			if (failure != null) {
				throw failure;
			}
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			InputStream input = new ByteArrayInputStream(bytes) {
				@Override
				public synchronized int read() {
					if (readFailure != null) {
						throw new SftpSourceException(MigrationErrorCodes.SFTP_READ_FAILED, "SFTP read failed", readFailure);
					}
					return super.read();
				}

				@Override
				public synchronized int read(byte[] buffer, int offset, int length) {
					if (readFailure != null) {
						throw new SftpSourceException(MigrationErrorCodes.SFTP_READ_FAILED, "SFTP read failed", readFailure);
					}
					return super.read(buffer, offset, length);
				}
			};
			return new SftpRemoteFile(input, bytes.length, () -> closedFlag.set(true));
		}

		private boolean closed() {
			return closedFlag.get();
		}

		private SftpConnectionRequest last() {
			return request.get();
		}
	}
}
