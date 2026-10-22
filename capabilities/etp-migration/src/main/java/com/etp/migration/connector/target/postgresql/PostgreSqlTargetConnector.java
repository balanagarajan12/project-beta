package com.etp.migration.connector.target.postgresql;

import com.etp.migration.connector.target.LoadableTargetRecord;
import com.etp.migration.connector.target.MigrationTargetConnector;
import com.etp.migration.connector.target.MigrationTargetContext;
import com.etp.migration.connector.target.TargetInfrastructureException;
import com.etp.migration.connector.target.TargetLoadModeNotSupportedException;
import com.etp.migration.connector.target.TargetLoadResult;
import com.etp.migration.entity.LoadMode;
import com.etp.migration.processing.EntityIdempotencyKey;
import com.etp.migration.processing.MigrationErrorCodes;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.QuoteMode;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.postgresql.copy.CopyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Generic PostgreSQL BULK target. Valid {@code TargetRecord} maps are streamed into a configured
 * staging table with {@code COPY ... FROM STDIN}. Canonical application tables (including UAM)
 * are never written. The worker still supplies a batch-sized list; this connector does not
 * accumulate an entire migration.
 */
@Component
public class PostgreSqlTargetConnector implements MigrationTargetConnector {

	private static final Logger log = LoggerFactory.getLogger(PostgreSqlTargetConnector.class);
	static final String TARGET_TYPE = "POSTGRESQL";
	private static final CSVFormat CSV = CSVFormat.DEFAULT.builder()
			.setQuoteMode(QuoteMode.MINIMAL)
			.setNullString("\\N")
			.setRecordSeparator("\n")
			.get();

	private final PostgreSqlTargetProperties properties;
	private final DataSource dataSource;
	private final ObjectMapper objectMapper;
	private final PostgreSqlConnectionRegistry connections;

	@Autowired
	public PostgreSqlTargetConnector(
			PostgreSqlTargetProperties properties,
			@Autowired(required = false) @Qualifier(PostgreSqlTargetClientConfig.DATASOURCE_BEAN) DataSource dataSource,
			@Autowired(required = false) PostgreSqlConnectionRegistry connections) {
		this(properties, dataSource, new ObjectMapper(), connections);
	}

	public PostgreSqlTargetConnector(PostgreSqlTargetProperties properties, DataSource dataSource, ObjectMapper objectMapper) {
		this(properties, dataSource, objectMapper, null);
	}

	public PostgreSqlTargetConnector(
			PostgreSqlTargetProperties properties,
			DataSource dataSource,
			ObjectMapper objectMapper,
			PostgreSqlConnectionRegistry connections) {
		this.properties = properties;
		this.dataSource = dataSource;
		this.objectMapper = objectMapper;
		this.connections = connections;
	}

	@Override
	public String targetType() {
		return TARGET_TYPE;
	}

	@Override
	public boolean supportsLoadMode(LoadMode loadMode) {
		return loadMode == LoadMode.BULK;
	}

	@Override
	public TargetLoadResult load(MigrationTargetContext context, List<LoadableTargetRecord> records) {
		LoadMode loadMode = context.loadMode() == null ? LoadMode.BULK : context.loadMode();
		if (!supportsLoadMode(loadMode)) {
			throw new TargetLoadModeNotSupportedException(TARGET_TYPE, loadMode);
		}
		DataSource source = PostgreSqlConnectionRegistry.stagingOf(connections, dataSource, context.configuration());
		PostgreSqlStagingTarget.Resolved staging = PostgreSqlStagingTarget.resolve(context, properties);
		int submitted = records == null ? 0 : records.size();
		if (submitted == 0) {
			return new TargetLoadResult(0, 0, 0, List.of(), List.of());
		}
		Instant started = Instant.now();
		long copied;
		try {
			copied = copyBatch(source, context, staging, records);
		} catch (TargetInfrastructureException ex) {
			throw ex;
		} catch (SQLException ex) {
			throw new TargetInfrastructureException(
					MigrationErrorCodes.TARGET_DATABASE_CONNECTION_ERROR,
					"PostgreSQL target connection failed",
					ex);
		}
		long durationMs = Duration.between(started, Instant.now()).toMillis();
		log.info(
				"PostgreSQL COPY finished runId={} batchId={} batchNumber={} targetType={} loadMode={} submittedCount={} successCount={} copyRows={} copyDurationMs={} durationMs={}",
				context.migrationRunId(),
				context.migrationBatchId(),
				context.batchNumber(),
				context.targetType(),
				loadMode,
				submitted,
				copied,
				copied,
				durationMs,
				durationMs);
		return new TargetLoadResult(submitted, submitted, 0, List.of(), List.of());
	}

	private long copyBatch(
			DataSource source,
			MigrationTargetContext context,
			PostgreSqlStagingTarget.Resolved staging,
			List<LoadableTargetRecord> records) throws SQLException {
		try (Connection connection = source.getConnection()) {
			boolean previousAutoCommit = connection.getAutoCommit();
			connection.setAutoCommit(false);
			try {
				replaceStagingRows(connection, staging, context);
				deleteEntityIdempotencyKeys(connection, staging, context, records);
				long copied = streamCopy(connection, staging, context, records);
				connection.commit();
				return copied;
			} catch (SQLException | IOException | RuntimeException ex) {
				rollbackQuietly(connection);
				if (ex instanceof TargetInfrastructureException infrastructure) {
					throw infrastructure;
				}
				throw new TargetInfrastructureException(
						MigrationErrorCodes.TARGET_COPY_FAILED,
						"PostgreSQL COPY failed",
						ex);
			} finally {
				connection.setAutoCommit(previousAutoCommit);
			}
		}
	}

	private void replaceStagingRows(
			Connection connection,
			PostgreSqlStagingTarget.Resolved staging,
			MigrationTargetContext context) throws SQLException {
		String sql = "DELETE FROM " + PostgreSqlStagingTarget.quoted(staging)
				+ " WHERE migration_run_id = ? AND migration_batch_id = ?";
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setObject(1, context.migrationRunId());
			statement.setLong(2, context.migrationBatchId());
			statement.executeUpdate();
		}
	}

	private void deleteEntityIdempotencyKeys(
			Connection connection,
			PostgreSqlStagingTarget.Resolved staging,
			MigrationTargetContext context,
			List<LoadableTargetRecord> records) throws SQLException {
		List<String> keys = new ArrayList<>();
		for (LoadableTargetRecord record : records) {
			if (EntityIdempotencyKey.isEntityKey(record.idempotencyKey())) {
				keys.add(record.idempotencyKey());
			}
		}
		int chunkSize = 500;
		for (int offset = 0; offset < keys.size(); offset += chunkSize) {
			List<String> chunk = keys.subList(offset, Math.min(offset + chunkSize, keys.size()));
			StringBuilder placeholders = new StringBuilder();
			for (int index = 0; index < chunk.size(); index++) {
				if (index > 0) {
					placeholders.append(',');
				}
				placeholders.append('?');
			}
			String sql = "DELETE FROM " + PostgreSqlStagingTarget.quoted(staging)
					+ " WHERE migration_run_id = ? AND idempotency_key IN (" + placeholders + ")";
			try (PreparedStatement statement = connection.prepareStatement(sql)) {
				statement.setObject(1, context.migrationRunId());
				for (int index = 0; index < chunk.size(); index++) {
					statement.setString(index + 2, chunk.get(index));
				}
				statement.executeUpdate();
			}
		}
	}

	private long streamCopy(
			Connection connection,
			PostgreSqlStagingTarget.Resolved staging,
			MigrationTargetContext context,
			List<LoadableTargetRecord> records) throws SQLException, IOException {
		PGConnection pgConnection = connection.unwrap(PGConnection.class);
		CopyManager copyManager = pgConnection.getCopyAPI();
		String copySql = "COPY " + PostgreSqlStagingTarget.quoted(staging)
				+ " (migration_run_id, migration_batch_id, source_record_number, target_payload, idempotency_key, loaded_at)"
				+ " FROM STDIN WITH (FORMAT csv, NULL '\\N')";
		CopyIn copyIn = copyManager.copyIn(copySql);
		try {
			String loadedAt = OffsetDateTime.now(ZoneOffset.UTC).toString();
			for (LoadableTargetRecord record : records) {
				byte[] line = csvLine(context, record, loadedAt);
				copyIn.writeToCopy(line, 0, line.length);
			}
			return copyIn.endCopy();
		} catch (IOException | RuntimeException | SQLException ex) {
			if (copyIn.isActive()) {
				copyIn.cancelCopy();
			}
			throw ex;
		}
	}

	private byte[] csvLine(MigrationTargetContext context, LoadableTargetRecord record, String loadedAt)
			throws IOException {
		StringWriter writer = new StringWriter();
		try (CSVPrinter printer = new CSVPrinter(writer, CSV)) {
			printer.printRecord(
					context.migrationRunId().toString(),
					context.migrationBatchId(),
					record.sourceRecordNumber(),
					payloadJson(record),
					blankToNull(record.idempotencyKey()),
					loadedAt);
		}
		return writer.toString().getBytes(StandardCharsets.UTF_8);
	}

	private String payloadJson(LoadableTargetRecord record) {
		Map<String, Object> fields = record.targetRecord() == null || record.targetRecord().fields() == null
				? Map.of()
				: record.targetRecord().fields();
		try {
			return objectMapper.writeValueAsString(fields);
		} catch (JsonProcessingException ex) {
			throw new TargetInfrastructureException(
					MigrationErrorCodes.TARGET_COPY_FAILED,
					"PostgreSQL COPY failed",
					ex);
		}
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	private static void rollbackQuietly(Connection connection) {
		try {
			connection.rollback();
		} catch (SQLException ex) {
			throw new TargetInfrastructureException(
					MigrationErrorCodes.TARGET_COPY_ROLLBACK,
					"PostgreSQL COPY rollback failed",
					ex);
		}
	}
}
