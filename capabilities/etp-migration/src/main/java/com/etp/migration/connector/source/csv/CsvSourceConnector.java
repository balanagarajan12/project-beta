package com.etp.migration.connector.source.csv;

import com.etp.migration.connector.source.InvalidSourceConfigurationException;
import com.etp.migration.connector.source.MigrationSourceConnector;
import com.etp.migration.connector.source.SourceBatch;
import com.etp.migration.connector.source.SourceInspection;
import com.etp.migration.connector.source.SourceInspectionException;
import com.etp.migration.connector.source.UnsupportedSourceTypeException;
import com.etp.migration.connector.storage.ArtifactMetadata;
import com.etp.migration.connector.storage.ArtifactStorage;
import com.etp.migration.entity.MigrationBatch;
import com.etp.migration.entity.MigrationRun;
import com.etp.migration.entity.MigrationSource;
import com.etp.migration.processing.SourceRecord;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class CsvSourceConnector implements MigrationSourceConnector {

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
		CsvRecordReader.Scan scan;
		try (InputStream input = artifactStorage.open(config.provider(), config.objectKey())) {
			scan = CsvRecordReader.scan(
					input,
					config.encoding(),
					config.delimiter(),
					config.quoteCharacter(),
					config.hasHeader());
		} catch (com.etp.migration.connector.storage.ArtifactNotFoundException
				| InvalidSourceConfigurationException
				| UnsupportedSourceTypeException ex) {
			throw ex;
		} catch (Exception ex) {
			throw new SourceInspectionException("Source inspection failed", ex);
		}
		String checksum = scan.checksum();
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
				scan.recordCount(),
				scan.columns(),
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
		try {
			InputStream input = artifactStorage.open(config.provider(), locator.objectKey());
			java.util.stream.Stream<SourceRecord> stream = CsvRecordReader.range(
					input,
					config.encoding(),
					config.delimiter(),
					config.quoteCharacter(),
					config.hasHeader(),
					locator.startRecord(),
					locator.endRecord());
			return new SourceBatch(batch.getBatchNumber(), batch.getSourceLocator(), stream);
		} catch (com.etp.migration.processing.BatchProcessingException
				| com.etp.migration.connector.storage.ArtifactNotFoundException
				| InvalidSourceConfigurationException
				| UnsupportedSourceTypeException ex) {
			throw ex;
		} catch (Exception ex) {
			throw new com.etp.migration.processing.BatchProcessingException(
					com.etp.migration.processing.MigrationErrorCodes.SOURCE_READ_ERROR,
					"Unable to read source batch",
					ex);
		}
	}

	public java.util.stream.Stream<SourceRecord> readAll(CsvFileSourceConfiguration config) {
		if (config == null) {
			throw new InvalidSourceConfigurationException("Source configuration is required");
		}
		if (!artifactStorage.exists(config.provider(), config.objectKey())) {
			throw new com.etp.migration.connector.storage.ArtifactNotFoundException(config.objectKey());
		}
		try {
			InputStream input = artifactStorage.open(config.provider(), config.objectKey());
			return CsvRecordReader.all(
					input,
					config.encoding(),
					config.delimiter(),
					config.quoteCharacter(),
					config.hasHeader());
		} catch (com.etp.migration.connector.storage.ArtifactNotFoundException
				| InvalidSourceConfigurationException
				| UnsupportedSourceTypeException
				| com.etp.migration.processing.BatchProcessingException ex) {
			throw ex;
		} catch (Exception ex) {
			throw new com.etp.migration.processing.BatchProcessingException(
					com.etp.migration.processing.MigrationErrorCodes.SOURCE_READ_ERROR,
					"Unable to read source batch",
					ex);
		}
	}

	static void requireFileSource(MigrationSource source) {
		if (source == null) {
			throw new InvalidSourceConfigurationException("Source type is required");
		}
		CsvFileSourceConfiguration.from(source.getConfiguration());
	}
}
