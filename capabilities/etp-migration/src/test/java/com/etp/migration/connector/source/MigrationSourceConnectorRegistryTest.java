package com.etp.migration.connector.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.etp.migration.connector.source.csv.CsvSourceConnector;
import com.etp.migration.connector.source.sftp.SftpConnectionRequest;
import com.etp.migration.connector.source.sftp.SftpCsvSourceConnector;
import com.etp.migration.connector.source.sftp.SftpRemoteClient;
import com.etp.migration.connector.source.sftp.SftpRemoteFile;
import com.etp.migration.connector.storage.DefaultArtifactStorage;
import com.etp.migration.connector.storage.FileSystemArtifactStorage;
import com.etp.migration.connector.storage.UnsupportedStorageProviderException;
import com.etp.migration.entity.MigrationSource;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationSourceConnectorRegistryTest {

	@TempDir
	Path tempDir;

	private CsvSourceConnector csvConnector;
	private MigrationSourceConnectorRegistry registry;

	@BeforeEach
	void setUp() {
		FileSystemArtifactStorage fileSystem = new FileSystemArtifactStorage(tempDir);
		fileSystem.initializeRoot();
		csvConnector = new CsvSourceConnector(new DefaultArtifactStorage(fileSystem));
		SftpCsvSourceConnector sftpConnector = new SftpCsvSourceConnector(new UnusedSftpClient(), ref -> {
			throw new IllegalStateException("unused");
		});
		registry = new MigrationSourceConnectorRegistry(List.of(csvConnector, sftpConnector));
	}

	@Test
	void fileSystemCsvConfigurationResolvesCsvConnector() {
		assertThat(registry.require(source("FILE_SYSTEM", csvConfiguration()))).isSameAs(csvConnector);
	}

	@Test
	void fileCsvConfigurationStillResolvesCsvConnector() {
		assertThat(registry.require(source("FILE", csvConfiguration()))).isSameAs(csvConnector);
	}

	@Test
	void unsupportedFileFormatIsRejected() {
		assertThatThrownBy(() -> registry.require(source("FILE_SYSTEM", Map.of(
				"provider", "FILE_SYSTEM",
				"objectKey", "demo/addresses.json",
				"fileFormat", "JSON"))))
				.isInstanceOf(UnsupportedFileFormatException.class);
	}

	@Test
	void missingObjectKeyIsRejected() {
		assertThatThrownBy(() -> registry.require(source("FILE_SYSTEM", Map.of(
				"provider", "FILE_SYSTEM",
				"fileFormat", "CSV"))))
				.isInstanceOf(InvalidSourceConfigurationException.class)
				.hasMessageContaining("objectKey");
	}

	@Test
	void sftpCsvConfigurationResolvesSftpConnector() {
		SftpCsvSourceConnector sftpConnector = new SftpCsvSourceConnector(new UnusedSftpClient(), ref -> {
			throw new IllegalStateException("unused");
		});
		MigrationSourceConnectorRegistry sftpRegistry = new MigrationSourceConnectorRegistry(List.of(csvConnector, sftpConnector));
		MigrationSource source = source("FILE", Map.of(
				"provider", "SFTP",
				"fileFormat", "CSV",
				"host", "sftp.example",
				"port", 22,
				"credentialRef", "local-sftp",
				"remotePath", "/inbound/rows.csv",
				"hasHeader", true,
				"delimiter", ","));

		assertThat(sftpRegistry.require(source)).isSameAs(sftpConnector);
	}

	@Test
	void unsupportedProviderIsRejected() {
		assertThatThrownBy(() -> registry.require(source("FILE_SYSTEM", Map.of(
				"provider", "S3",
				"objectKey", "demo/addresses.csv",
				"fileFormat", "CSV"))))
				.isInstanceOf(UnsupportedStorageProviderException.class);
	}

	private static MigrationSource source(String sourceType, Map<String, Object> configuration) {
		MigrationSource source = new MigrationSource();
		source.setSourceType(sourceType);
		source.setConfiguration(new LinkedHashMap<>(configuration));
		return source;
	}

	private static Map<String, Object> csvConfiguration() {
		Map<String, Object> configuration = new LinkedHashMap<>();
		configuration.put("provider", "FILE_SYSTEM");
		configuration.put("objectKey", "demo/addresses.csv");
		configuration.put("fileFormat", "CSV");
		configuration.put("hasHeader", true);
		return configuration;
	}

	private static final class UnusedSftpClient implements SftpRemoteClient {
		@Override
		public SftpRemoteFile open(SftpConnectionRequest request) {
			throw new IllegalStateException("unused");
		}
	}
}
