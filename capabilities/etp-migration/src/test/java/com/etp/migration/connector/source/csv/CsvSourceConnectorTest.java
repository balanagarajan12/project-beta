package com.etp.migration.connector.source.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.etp.migration.connector.source.InvalidSourceConfigurationException;
import com.etp.migration.connector.source.SourceInspection;
import com.etp.migration.connector.source.UnsupportedFileFormatException;
import com.etp.migration.connector.storage.ArtifactNotFoundException;
import com.etp.migration.connector.storage.DefaultArtifactStorage;
import com.etp.migration.connector.storage.FileSystemArtifactStorage;
import com.etp.migration.connector.storage.InvalidArtifactPathException;
import com.etp.migration.entity.MigrationRun;
import com.etp.migration.entity.MigrationSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CsvSourceConnectorTest {

	@TempDir
	Path tempDir;

	private CsvSourceConnector connector;

	@BeforeEach
	void setUp() {
		FileSystemArtifactStorage fileSystem = new FileSystemArtifactStorage(tempDir);
		fileSystem.initializeRoot();
		connector = new CsvSourceConnector(new DefaultArtifactStorage(fileSystem));
	}

	@Test
	void discoversHeaderAndQuotedFields() throws Exception {
		write("quoted.csv", """
				name,city
				"Doe, John",Springfield
				"",Boston
				""");

		SourceInspection inspection = connector.inspect(source("quoted.csv", true), new MigrationRun());

		assertThat(inspection.columns()).extracting("name").containsExactly("name", "city");
		assertThat(inspection.recordCount()).isEqualTo(2L);
		assertThat(inspection.columns().get(0).ordinalPosition()).isEqualTo(1);
		assertThat(inspection.columns().get(0).inferredType()).isEqualTo("STRING");
	}

	@Test
	void countsRowsWithoutHeader() throws Exception {
		write("no-header.csv", """
				a,b
				c,d
				e,f
				""");

		SourceInspection inspection = connector.inspect(source("no-header.csv", false), new MigrationRun());

		assertThat(inspection.recordCount()).isEqualTo(3L);
		assertThat(inspection.columns()).extracting("name").containsExactly("column_1", "column_2");
	}

	@Test
	void headerIsExcludedFromRecordCount() throws Exception {
		write("header-only.csv", "addr1,city\n");

		SourceInspection inspection = connector.inspect(source("header-only.csv", true), new MigrationRun());

		assertThat(inspection.recordCount()).isEqualTo(0L);
		assertThat(inspection.columns()).extracting("name").containsExactly("addr1", "city");
	}

	@Test
	void checksumIsSha256OfFileBytes() throws Exception {
		String content = "addr1,city\n1,x\n";
		write("checksum.csv", content);
		String expected = HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));

		SourceInspection inspection = connector.inspect(source("checksum.csv", true), new MigrationRun());

		assertThat(inspection.checksum()).isEqualTo(expected);
		assertThat(inspection.checksum()).isLowerCase();
		assertThat(inspection.sizeBytes()).isEqualTo(content.getBytes(StandardCharsets.UTF_8).length);
	}

	@Test
	void missingFileFails() {
		assertThatThrownBy(() -> connector.inspect(source("missing.csv", true), new MigrationRun()))
				.isInstanceOf(ArtifactNotFoundException.class);
	}

	@Test
	void supportsFileSystemCsvConfiguration() {
		MigrationSource source = source("FILE_SYSTEM", "demo/addresses.csv", true);
		assertThat(connector.supports(source)).isTrue();
	}

	@Test
	void inspectsFileSystemSourceTypeWithoutRequiringFileLabel() throws Exception {
		write("filesystem.csv", """
				addr1,city
				123 Main St,Salem
				""");

		SourceInspection inspection = connector.inspect(source("FILE_SYSTEM", "filesystem.csv", true), new MigrationRun());

		assertThat(inspection.recordCount()).isEqualTo(1L);
		assertThat(inspection.columns()).extracting("name").containsExactly("addr1", "city");
	}

	@Test
	void invalidConfigurationFails() {
		MigrationSource source = new MigrationSource();
		source.setSourceType("FILE");
		source.setConfiguration(Map.of("provider", "FILE_SYSTEM", "fileFormat", "CSV"));
		assertThatThrownBy(() -> CsvFileSourceConfiguration.from(source.getConfiguration()))
				.isInstanceOf(InvalidSourceConfigurationException.class);
	}

	@Test
	void unsupportedFormatFails() {
		assertThatThrownBy(() -> CsvFileSourceConfiguration.from(Map.of(
				"provider", "FILE_SYSTEM",
				"objectKey", "samples/x.json",
				"fileFormat", "JSON")))
				.isInstanceOf(UnsupportedFileFormatException.class);
	}

	@Test
	void pathTraversalFails() {
		MigrationSource source = source("../secret.csv", true);
		assertThatThrownBy(() -> connector.inspect(source, new MigrationRun()))
				.isInstanceOf(InvalidArtifactPathException.class);
	}

	@Test
	void readBatchReturnsRequestedRowRange() throws Exception {
		write("range.csv", """
				name,city
				one,a
				two,"b, quoted"
				three,
				four,d
				five,e
				""");
		var first = read("range.csv", 1, 1);
		assertThat(first).hasSize(1);
		assertThat(first.get(0).recordNumber()).isEqualTo(1L);
		assertThat(first.get(0).fields().get("name")).isEqualTo("one");

		var middle = read("range.csv", 2, 3);
		assertThat(middle).extracting(com.etp.migration.processing.SourceRecord::recordNumber)
				.containsExactly(2L, 3L);
		assertThat(middle.get(0).fields().get("city")).isEqualTo("b, quoted");
		assertThat(middle.get(1).fields().get("city")).isEqualTo("");

		var last = read("range.csv", 4, 5);
		assertThat(last).hasSize(2);
		assertThat(last.get(1).fields().get("name")).isEqualTo("five");
	}

	@Test
	void readBatchStreamsLargeFileWithoutMaterializingAllRowsUpFront() throws Exception {
		Path path = tempDir.resolve("large.csv");
		try (var writer = Files.newBufferedWriter(path)) {
			writer.write("id,name\n");
			for (int i = 1; i <= 100_000; i++) {
				writer.write(i + ",n" + i + "\n");
			}
		}
		try (var batch = connector.readBatch(source("large.csv", true), batch("large.csv", 1, 100_000))) {
			long[] seen = {0};
			batch.records().forEach(record -> {
				seen[0]++;
				assertThat(record.recordNumber()).isEqualTo(seen[0]);
			});
			assertThat(seen[0]).isEqualTo(100_000L);
		}
	}

	private List<com.etp.migration.processing.SourceRecord> read(String objectKey, long start, long end) {
		try (var batch = connector.readBatch(source(objectKey, true), batch(objectKey, start, end))) {
			return batch.records().toList();
		}
	}

	private static com.etp.migration.entity.MigrationBatch batch(String objectKey, long start, long end) {
		var batch = new com.etp.migration.entity.MigrationBatch();
		batch.setBatchNumber(1);
		batch.setSourceLocator(Map.of(
				"type", "CSV_ROW_RANGE",
				"objectKey", objectKey,
				"startRecord", start,
				"endRecord", end));
		return batch;
	}

	private MigrationSource source(String objectKey, boolean hasHeader) {
		return source("FILE", objectKey, hasHeader);
	}

	private MigrationSource source(String sourceType, String objectKey, boolean hasHeader) {
		MigrationSource source = new MigrationSource();
		source.setSourceType(sourceType);
		Map<String, Object> configuration = new LinkedHashMap<>();
		configuration.put("provider", "FILE_SYSTEM");
		configuration.put("objectKey", objectKey);
		configuration.put("fileFormat", "CSV");
		configuration.put("delimiter", ",");
		configuration.put("hasHeader", hasHeader);
		configuration.put("encoding", "UTF-8");
		configuration.put("quoteCharacter", "\"");
		source.setConfiguration(configuration);
		return source;
	}

	private void write(String objectKey, String content) throws Exception {
		Path path = tempDir.resolve(objectKey);
		Files.createDirectories(path.getParent() == null ? tempDir : path.getParent());
		Files.writeString(path, content);
	}
}
