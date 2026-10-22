package com.etp.migration.connector.target.postgresql;

import com.etp.migration.connector.target.LoadableTargetRecord;
import com.etp.migration.connector.target.TargetInfrastructureException;
import com.etp.migration.processing.MigrationErrorCodes;
import com.etp.migration.processing.TargetRecord;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.postgresql.util.PGobject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Reads one staged batch and rebuilds the {@link LoadableTargetRecord} list the target connector received.
 * Null target identities stay null. This reader does not allocate identities or write business tables.
 */
@Component
public class PostgreSqlStagedEntityReader {

	private static final TypeReference<LinkedHashMap<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {
	};

	private final PostgreSqlTargetProperties properties;
	private final DataSource dataSource;
	private final ObjectMapper objectMapper;
	private final PostgreSqlConnectionRegistry connections;

	@Autowired
	public PostgreSqlStagedEntityReader(
			PostgreSqlTargetProperties properties,
			@Autowired(required = false) @Qualifier(PostgreSqlTargetClientConfig.DATASOURCE_BEAN) DataSource dataSource,
			@Autowired(required = false) PostgreSqlConnectionRegistry connections) {
		this(properties, dataSource, new ObjectMapper(), connections);
	}

	PostgreSqlStagedEntityReader(
			PostgreSqlTargetProperties properties,
			DataSource dataSource,
			ObjectMapper objectMapper) {
		this(properties, dataSource, objectMapper, null);
	}

	PostgreSqlStagedEntityReader(
			PostgreSqlTargetProperties properties,
			DataSource dataSource,
			ObjectMapper objectMapper,
			PostgreSqlConnectionRegistry connections) {
		this.properties = properties;
		this.dataSource = dataSource;
		this.objectMapper = objectMapper;
		this.connections = connections;
	}

	public List<LoadableTargetRecord> read(UUID migrationRunId, Long migrationBatchId, Map<String, Object> configuration) {
		if (migrationRunId == null) {
			throw new IllegalArgumentException("migration run id is required");
		}
		if (migrationBatchId == null) {
			throw new IllegalArgumentException("migration batch id is required");
		}
		DataSource source = PostgreSqlConnectionRegistry.stagingOf(connections, dataSource, configuration);
		PostgreSqlStagingTarget.Resolved staging = PostgreSqlStagingTarget.resolve(configuration, properties);
		String sql = "SELECT source_record_number, target_payload, idempotency_key FROM "
				+ PostgreSqlStagingTarget.quoted(staging)
				+ " WHERE migration_run_id = ? AND migration_batch_id = ?"
				+ " ORDER BY source_record_number, idempotency_key";
		long started = com.etp.migration.processing.PipelineTiming.start();
		try (Connection connection = source.getConnection()) {
			PostgreSqlStagingIndexes.ensureBatchLookup(connection, staging);
			try (PreparedStatement statement = connection.prepareStatement(sql)) {
				statement.setObject(1, migrationRunId);
				statement.setLong(2, migrationBatchId);
				try (ResultSet resultSet = statement.executeQuery()) {
					List<LoadableTargetRecord> records = new ArrayList<>();
					while (resultSet.next()) {
						records.add(toRecord(
								resultSet.getLong("source_record_number"),
								resultSet.getString("idempotency_key"),
								payload(resultSet.getObject("target_payload"))));
					}
					com.etp.migration.processing.PipelineTiming.add("stagingRowsRead", records.size());
					return List.copyOf(records);
				}
			}
		} catch (TargetInfrastructureException ex) {
			throw ex;
		} catch (SQLException ex) {
			throw new TargetInfrastructureException(
					MigrationErrorCodes.TARGET_DATABASE_CONNECTION_ERROR,
					"PostgreSQL staged entity read failed",
					ex);
		} finally {
			com.etp.migration.processing.PipelineTiming.stop(
					com.etp.migration.processing.PipelineTiming.Stage.STAGING_READ, started);
		}
	}

	static LoadableTargetRecord toRecord(long sourceRecordNumber, String idempotencyKey, Map<String, Object> payload) {
		Map<String, Object> fields = payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
		return new LoadableTargetRecord(sourceRecordNumber, new TargetRecord(fields), idempotencyKey);
	}

	private Map<String, Object> payload(Object raw) {
		try {
			if (raw == null) {
				return new LinkedHashMap<>();
			}
			if (raw instanceof Map<?, ?> map) {
				Map<String, Object> fields = new LinkedHashMap<>();
				for (Map.Entry<?, ?> entry : map.entrySet()) {
					if (entry.getKey() != null) {
						fields.put(entry.getKey().toString(), entry.getValue());
					}
				}
				return fields;
			}
			String json = raw instanceof PGobject pgObject ? pgObject.getValue() : raw.toString();
			if (json == null || json.isBlank()) {
				return new LinkedHashMap<>();
			}
			return objectMapper.readValue(json, PAYLOAD_TYPE);
		} catch (Exception ex) {
			throw new TargetInfrastructureException(
					MigrationErrorCodes.TARGET_DATABASE_CONNECTION_ERROR,
					"Staged entity payload is invalid",
					ex);
		}
	}
}
