package com.etp.migration.connector.source.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.etp.migration.connector.source.InvalidSourceConfigurationException;
import com.etp.migration.connector.source.SourceInspection;
import com.etp.migration.connector.storage.DefaultArtifactStorage;
import com.etp.migration.connector.storage.FileSystemArtifactStorage;
import com.etp.migration.entity.MigrationBatch;
import com.etp.migration.entity.MigrationRun;
import com.etp.migration.entity.MigrationSource;
import com.etp.migration.processing.SourceRecord;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CsvHeaderHandlingTest {

	private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

	@TempDir
	Path tempDir;

	@Test
	void duplicateHeaderIsRejectedWhenScanning() {
		assertThatThrownBy(() -> CsvRecordReader.scan(input("id,name,id\n1,a,2\n"), StandardCharsets.UTF_8, ',', '"', true))
				.isInstanceOf(InvalidSourceConfigurationException.class)
				.hasMessageContaining("duplicate column name 'id'");
	}

	@Test
	void duplicateHeaderIsRejectedWhenReadingRecords() {
		assertThatThrownBy(() -> {
			try (Stream<SourceRecord> records = CsvRecordReader.all(
					input("id,name,id\n1,a,2\n"), StandardCharsets.UTF_8, ',', '"', true)) {
				records.toList();
			}
		}).isInstanceOf(InvalidSourceConfigurationException.class)
				.hasMessageContaining("duplicate column name 'id'");
	}

	@Test
	void headerComparisonIsCaseSensitiveAndDoesNotTrimWhitespace() throws Exception {
		CsvRecordReader.Scan scan = CsvRecordReader.scan(
				input("id,ID, id\n1,2,3\n"), StandardCharsets.UTF_8, ',', '"', true);
		List<SourceRecord> records;
		try (Stream<SourceRecord> stream = CsvRecordReader.all(
				input("id,ID, id\n1,2,3\n"), StandardCharsets.UTF_8, ',', '"', true)) {
			records = stream.toList();
		}

		assertThat(scan.columns()).extracting("name").containsExactly("id", "ID", " id");
		assertThat(records.get(0).fields())
				.containsEntry("id", "1")
				.containsEntry("ID", "2")
				.containsEntry(" id", "3");
	}

	@Test
	void utf8BomIsNotPartOfTheFirstHeader() throws Exception {
		byte[] bytes = withBom("id,name\n1,North\n");

		CsvRecordReader.Scan scan = CsvRecordReader.scan(
				new ByteArrayInputStream(bytes), StandardCharsets.UTF_8, ',', '"', true);
		List<SourceRecord> records;
		try (Stream<SourceRecord> stream = CsvRecordReader.all(
				new ByteArrayInputStream(bytes), StandardCharsets.UTF_8, ',', '"', true)) {
			records = stream.toList();
		}

		assertThat(scan.columns()).extracting("name").containsExactly("id", "name");
		assertThat(records.get(0).fields()).containsEntry("id", "1").containsKey("id").doesNotContainKey("\uFEFFid");
		assertThat(scan.checksum()).isEqualTo(sha256(bytes));
	}

	@Test
	void utf8BomIsNotPartOfTheFirstValueWithoutHeader() {
		List<SourceRecord> records;
		try (Stream<SourceRecord> stream = CsvRecordReader.all(
				new ByteArrayInputStream(withBom("1,North\n")), StandardCharsets.UTF_8, ',', '"', false)) {
			records = stream.toList();
		}

		assertThat(records.get(0).fields()).containsEntry("column_1", "1");
	}

	@Test
	void bomDoesNotHideADuplicateFirstHeader() {
		assertThatThrownBy(() -> CsvRecordReader.scan(
				new ByteArrayInputStream(withBom("id,id\n1,2\n")), StandardCharsets.UTF_8, ',', '"', true))
				.isInstanceOf(InvalidSourceConfigurationException.class)
				.hasMessageContaining("duplicate column name 'id'");
	}

	@Test
	void fileSystemConnectorRejectsDuplicateHeaderAsSourceProblem() throws Exception {
		Files.writeString(tempDir.resolve("dup.csv"), "code,code\nA,B\n");
		CsvSourceConnector connector = connector();

		assertThatThrownBy(() -> connector.inspect(source("dup.csv"), new MigrationRun()))
				.isInstanceOf(InvalidSourceConfigurationException.class)
				.hasMessageContaining("duplicate column name 'code'");
	}

	@Test
	void fileSystemConnectorReadsBomFileWithCleanHeadersAndBatchValues() throws Exception {
		Files.write(tempDir.resolve("bom.csv"), withBom("code,label\nA1,North\nB2,South\n"));
		CsvSourceConnector connector = connector();

		SourceInspection inspection = connector.inspect(source("bom.csv"), new MigrationRun());
		List<SourceRecord> records;
		try (var batch = connector.readBatch(source("bom.csv"), batch("bom.csv", 2, 2))) {
			records = batch.records().toList();
		}

		assertThat(inspection.columns()).extracting("name").containsExactly("code", "label");
		assertThat(records).singleElement().satisfies(record -> {
			assertThat(record.recordNumber()).isEqualTo(2L);
			assertThat(record.fields()).containsEntry("code", "B2");
		});
	}

	private CsvSourceConnector connector() {
		FileSystemArtifactStorage fileSystem = new FileSystemArtifactStorage(tempDir);
		fileSystem.initializeRoot();
		return new CsvSourceConnector(new DefaultArtifactStorage(fileSystem));
	}

	private static MigrationSource source(String objectKey) {
		MigrationSource source = new MigrationSource();
		source.setSourceType("FILE");
		Map<String, Object> configuration = new LinkedHashMap<>();
		configuration.put("provider", "FILE_SYSTEM");
		configuration.put("objectKey", objectKey);
		configuration.put("fileFormat", "CSV");
		configuration.put("hasHeader", true);
		source.setConfiguration(configuration);
		return source;
	}

	private static MigrationBatch batch(String objectKey, long start, long end) {
		MigrationBatch batch = new MigrationBatch();
		batch.setBatchNumber(1);
		batch.setSourceLocator(Map.of(
				"type", "CSV_ROW_RANGE",
				"objectKey", objectKey,
				"startRecord", start,
				"endRecord", end));
		return batch;
	}

	private static ByteArrayInputStream input(String text) {
		return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
	}

	private static byte[] withBom(String text) {
		byte[] body = text.getBytes(StandardCharsets.UTF_8);
		byte[] bytes = new byte[UTF8_BOM.length + body.length];
		System.arraycopy(UTF8_BOM, 0, bytes, 0, UTF8_BOM.length);
		System.arraycopy(body, 0, bytes, UTF8_BOM.length, body.length);
		return bytes;
	}

	private static String sha256(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}
}
