package com.etp.migration.connector.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.etp.migration.connector.source.csv.CsvSourceConnector;
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
		registry = new MigrationSourceConnectorRegistry(List.of(csvConnector));
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
}
