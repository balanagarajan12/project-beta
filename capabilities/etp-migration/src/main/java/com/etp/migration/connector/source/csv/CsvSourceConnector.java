package com.etp.migration.connector.source.csv;

import com.etp.migration.connector.source.InvalidSourceConfigurationException;
import com.etp.migration.connector.source.MigrationSourceConnector;
import com.etp.migration.connector.source.SourceBatch;
import com.etp.migration.connector.source.SourceColumn;
import com.etp.migration.connector.source.SourceInspection;
import com.etp.migration.connector.source.SourceInspectionException;
import com.etp.migration.connector.source.UnsupportedSourceTypeException;
import com.etp.migration.connector.storage.ArtifactMetadata;
import com.etp.migration.connector.storage.ArtifactStorage;
import com.etp.migration.entity.MigrationBatch;
import com.etp.migration.entity.MigrationRun;
import com.etp.migration.entity.MigrationSource;
import com.etp.migration.processing.SourceRecord;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

@Component
public class CsvSourceConnector implements MigrationSourceConnector {

	private static final String SHA_256 = "SHA-256";

	private final ArtifactStorage artifactStorage;

	public CsvSourceConnector(ArtifactStorage artifactStorage) {
		this.artifactStorage = artifactStorage;
	}

	@Override
	public boolean supports(MigrationSource source) {
		if (source == null) {
			return false;
		}
		try {
			CsvFileSourceConfiguration.from(source.getConfiguration());
			return true;
		} catch (RuntimeException ex) {
			return false;
		}
	}

	@Override
	public SourceInspection inspect(MigrationSource source, MigrationRun run) {
		requireFileSource(source);
		CsvFileSourceConfiguration config = CsvFileSourceConfiguration.from(source.getConfiguration());
		if (!artifactStorage.exists(config.provider(), config.objectKey())) {
			throw new com.etp.migration.connector.storage.ArtifactNotFoundException(config.objectKey());
		}
		ArtifactMetadata metadata = artifactStorage.metadata(config.provider(), config.objectKey());
		MessageDigest digest = sha256();
		List<SourceColumn> columns = List.of();
		long recordCount = 0L;
		try (InputStream input = artifactStorage.open(config.provider(), config.objectKey());
				DigestInputStream digestStream = new DigestInputStream(input, digest);
				BufferedReader reader = new BufferedReader(new InputStreamReader(digestStream, config.encoding()));
				CSVParser parser = csvFormat(config).parse(reader)) {
			if (config.hasHeader()) {
				columns = toColumns(parser.getHeaderNames());
			}
			Iterator<CSVRecord> records = parser.iterator();
			while (records.hasNext()) {
				CSVRecord record = records.next();
				if (!config.hasHeader() && columns.isEmpty()) {
					columns = syntheticColumns(record.size());
				}
				recordCount++;
			}
		} catch (com.etp.migration.connector.storage.ArtifactNotFoundException
				| InvalidSourceConfigurationException
				| UnsupportedSourceTypeException ex) {
			throw ex;
		} catch (Exception ex) {
			throw new SourceInspectionException("Source inspection failed", ex);
		}
		String checksum = HexFormat.of().formatHex(digest.digest());
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put("provider", config.provider().name());
		extra.put("objectKey", config.objectKey());
		if (config.bucket() != null) {
			extra.put("bucket", config.bucket());
		}
		extra.put("delimiter", String.valueOf(config.delimiter()));
		extra.put("hasHeader", config.hasHeader());
		extra.put("encoding", config.encoding().name());
		return new SourceInspection(
				recordCount,
				columns,
				checksum,
				metadata.sizeBytes(),
				config.fileFormat().name(),
				extra);
	}

	@Override
	public SourceBatch readBatch(MigrationSource source, MigrationBatch batch) {
		requireFileSource(source);
		CsvFileSourceConfiguration config = CsvFileSourceConfiguration.from(source.getConfiguration());
		CsvRowRangeLocator locator = CsvRowRangeLocator.from(batch.getSourceLocator());
		if (!artifactStorage.exists(config.provider(), locator.objectKey())) {
			throw new com.etp.migration.connector.storage.ArtifactNotFoundException(locator.objectKey());
		}
		InputStream input = null;
		BufferedReader reader = null;
		CSVParser parser = null;
		try {
			input = artifactStorage.open(config.provider(), locator.objectKey());
			reader = new BufferedReader(new InputStreamReader(input, config.encoding()));
			parser = csvFormat(config).parse(reader);
			List<String> headers = config.hasHeader() ? List.copyOf(parser.getHeaderNames()) : List.of();
			Iterator<CSVRecord> iterator = parser.iterator();
			long skipped = 0;
			while (skipped < locator.startRecord() - 1) {
				if (!iterator.hasNext()) {
					throw new com.etp.migration.processing.BatchProcessingException(
							com.etp.migration.processing.MigrationErrorCodes.SOURCE_READ_ERROR,
							"Source does not contain the requested batch range");
				}
				iterator.next();
				skipped++;
			}
			final CSVParser openParser = parser;
			final BufferedReader openReader = reader;
			final InputStream openInput = input;
			final Iterator<CSVRecord> records = iterator;
			java.util.stream.Stream<com.etp.migration.processing.SourceRecord> stream =
					java.util.stream.StreamSupport.stream(
									java.util.Spliterators.spliteratorUnknownSize(
											new Iterator<com.etp.migration.processing.SourceRecord>() {
												long remaining = locator.endRecord() - locator.startRecord() + 1;
												long recordNumber = locator.startRecord();

												@Override
												public boolean hasNext() {
													return remaining > 0 && records.hasNext();
												}

												@Override
												public com.etp.migration.processing.SourceRecord next() {
													if (!hasNext()) {
														throw new java.util.NoSuchElementException();
													}
													CSVRecord csvRecord = records.next();
													remaining--;
													Map<String, Object> fields = toFields(csvRecord, headers, config.hasHeader());
													return new com.etp.migration.processing.SourceRecord(recordNumber++, fields);
												}
											},
											java.util.Spliterator.ORDERED),
									false)
							.onClose(() -> closeQuietly(openParser, openReader, openInput));
			return new SourceBatch(batch.getBatchNumber(), batch.getSourceLocator(), stream);
		} catch (com.etp.migration.processing.BatchProcessingException
				| com.etp.migration.connector.storage.ArtifactNotFoundException
				| InvalidSourceConfigurationException
				| UnsupportedSourceTypeException ex) {
			closeQuietly(parser, reader, input);
			throw ex;
		} catch (Exception ex) {
			closeQuietly(parser, reader, input);
			throw new com.etp.migration.processing.BatchProcessingException(
					com.etp.migration.processing.MigrationErrorCodes.SOURCE_READ_ERROR,
					"Unable to read source batch",
					ex);
		}
	}

	private static Map<String, Object> toFields(CSVRecord csvRecord, List<String> headers, boolean hasHeader) {
		Map<String, Object> fields = new LinkedHashMap<>();
		if (hasHeader && !headers.isEmpty()) {
			for (String header : headers) {
				fields.put(header, csvRecord.isMapped(header) ? csvRecord.get(header) : null);
			}
			return fields;
		}
		for (int i = 0; i < csvRecord.size(); i++) {
			fields.put("column_" + (i + 1), csvRecord.get(i));
		}
		return fields;
	}

	private static void closeQuietly(AutoCloseable... resources) {
		for (AutoCloseable resource : resources) {
			if (resource != null) {
				try {
					resource.close();
				} catch (Exception ignored) {
					// close quietly after a read failure
				}
			}
		}
	}

	static void requireFileSource(MigrationSource source) {
		if (source == null) {
			throw new InvalidSourceConfigurationException("Source type is required");
		}
		CsvFileSourceConfiguration.from(source.getConfiguration());
	}

	private static CSVFormat csvFormat(CsvFileSourceConfiguration config) {
		CSVFormat.Builder builder = CSVFormat.DEFAULT.builder()
				.setDelimiter(config.delimiter())
				.setQuote(config.quoteCharacter())
				.setIgnoreEmptyLines(true)
				.setTrim(false);
		if (config.hasHeader()) {
			builder.setHeader().setSkipHeaderRecord(true);
		}
		return builder.get();
	}

	private static List<SourceColumn> toColumns(List<String> names) {
		List<SourceColumn> columns = new ArrayList<>();
		int ordinal = 1;
		for (String name : names) {
			String columnName = name == null || name.isBlank() ? "column_" + ordinal : name;
			columns.add(new SourceColumn(columnName, "STRING", ordinal, true));
			ordinal++;
		}
		return List.copyOf(columns);
	}

	private static List<SourceColumn> syntheticColumns(int size) {
		List<SourceColumn> columns = new ArrayList<>();
		for (int i = 1; i <= size; i++) {
			columns.add(new SourceColumn("column_" + i, "STRING", i, true));
		}
		return List.copyOf(columns);
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance(SHA_256);
		} catch (NoSuchAlgorithmException ex) {
			throw new SourceInspectionException("SHA-256 is not available", ex);
		}
	}
}
