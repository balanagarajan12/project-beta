package com.etp.migration.connector.source.sftp;

import com.etp.migration.connector.source.InvalidSourceConfigurationException;
import com.etp.migration.connector.source.MigrationSourceConnector;
import com.etp.migration.connector.source.SftpSourceException;
import com.etp.migration.connector.source.SourceBatch;
import com.etp.migration.connector.source.SourceInspection;
import com.etp.migration.connector.source.csv.CsvRecordReader;
import com.etp.migration.connector.source.csv.CsvRowRangeLocator;
import com.etp.migration.entity.MigrationBatch;
import com.etp.migration.entity.MigrationRun;
import com.etp.migration.entity.MigrationSource;
import com.etp.migration.processing.SourceRecord;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

@Component
public class SftpCsvSourceConnector implements MigrationSourceConnector {

	private final SftpRemoteClient client;
	private final SftpCredentialResolver credentials;

	public SftpCsvSourceConnector(SftpRemoteClient client, SftpCredentialResolver credentials) {
		this.client = client;
		this.credentials = credentials;
	}

	@Override
	public boolean supports(MigrationSource source) {
		if (source == null || !SftpCsvSourceConfiguration.declaresSftp(source.getConfiguration())) {
			return false;
		}
		SftpCsvSourceConfiguration.from(source.getConfiguration());
		return true;
	}

	@Override
	public SourceInspection inspect(MigrationSource source, MigrationRun run) {
		SftpCsvSourceConfiguration config = SftpCsvSourceConfiguration.from(source.getConfiguration());
		SftpRemoteFile remote = open(config);
		try {
			CsvRecordReader.Scan scan = CsvRecordReader.scan(
					remote.stream(),
					config.encoding(),
					config.delimiter(),
					config.quoteCharacter(),
					config.hasHeader());
			Map<String, Object> extra = new LinkedHashMap<>();
			extra.put("provider", "SFTP");
			extra.put("objectKey", config.remotePath());
			extra.put("remotePath", config.remotePath());
			extra.put("delimiter", String.valueOf(config.delimiter()));
			extra.put("hasHeader", config.hasHeader());
			extra.put("encoding", config.encoding().name());
			return new SourceInspection(
					scan.recordCount(),
					scan.columns(),
					scan.checksum(),
					remote.sizeBytes(),
					config.fileFormat().name(),
					extra);
		} catch (SftpSourceException ex) {
			throw ex;
		} catch (Exception ex) {
			throw propagate(ex);
		} finally {
			close(remote);
		}
	}

	@Override
	public SourceBatch readBatch(MigrationSource source, MigrationBatch batch) {
		SftpCsvSourceConfiguration config = SftpCsvSourceConfiguration.from(source.getConfiguration());
		CsvRowRangeLocator locator = CsvRowRangeLocator.from(batch.getSourceLocator());
		SftpRemoteFile remote = open(config, locator.objectKey());
		try {
			Stream<SourceRecord> records = stream(
					remote,
					config,
					locator.startRecord(),
					locator.endRecord(),
					true);
			return new SourceBatch(batch.getBatchNumber(), batch.getSourceLocator(), records);
		} catch (RuntimeException ex) {
			close(remote);
			throw propagate(ex);
		}
	}

	public Stream<SourceRecord> readAll(SftpCsvSourceConfiguration config) {
		if (config == null) {
			throw new InvalidSourceConfigurationException("Source configuration is required");
		}
		SftpRemoteFile remote = open(config);
		try {
			return stream(remote, config, 1L, Long.MAX_VALUE, false);
		} catch (RuntimeException ex) {
			close(remote);
			throw propagate(ex);
		}
	}

	private Stream<SourceRecord> stream(
			SftpRemoteFile remote,
			SftpCsvSourceConfiguration config,
			long startRecord,
			long endRecord,
			boolean bounded) {
		Stream<SourceRecord> records = bounded
				? CsvRecordReader.range(
						remote.stream(),
						config.encoding(),
						config.delimiter(),
						config.quoteCharacter(),
						config.hasHeader(),
						startRecord,
						endRecord,
						SftpSourceFailures::translate)
				: CsvRecordReader.all(
						remote.stream(),
						config.encoding(),
						config.delimiter(),
						config.quoteCharacter(),
						config.hasHeader(),
						SftpSourceFailures::translate);
		return records.onClose(() -> close(remote));
	}

	private SftpRemoteFile open(SftpCsvSourceConfiguration config) {
		return open(config, config.remotePath());
	}

	private SftpRemoteFile open(SftpCsvSourceConfiguration config, String remotePath) {
		SftpCredential credential = credentials.resolve(config.credentialRef());
		return client.open(new SftpConnectionRequest(
				config.host(),
				config.port(),
				credential.username(),
				credential.password(),
				remotePath,
				config.credentialRef()));
	}

	private static RuntimeException propagate(Throwable failure) {
		RuntimeException translated = SftpSourceFailures.translate(failure);
		if (translated instanceof RuntimeException runtime) {
			return runtime;
		}
		return new SftpSourceException(
				com.etp.migration.processing.MigrationErrorCodes.SFTP_READ_FAILED,
				"SFTP read failed",
				failure);
	}

	private static void close(SftpRemoteFile remote) {
		if (remote == null) {
			return;
		}
		try {
			remote.close();
		} catch (Exception ignored) {
			// the caller reports the original failure
		}
	}
}
