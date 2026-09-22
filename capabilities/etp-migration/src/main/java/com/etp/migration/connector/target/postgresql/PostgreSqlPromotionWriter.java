package com.etp.migration.connector.target.postgresql;

import com.etp.migration.connector.target.TargetInfrastructureException;
import com.etp.migration.processing.MigrationErrorCodes;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.postgresql.util.PGobject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class PostgreSqlPromotionWriter {

	private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {
	};

	private final PostgreSqlTargetProperties properties;
	private final DataSource dataSource;
	private final ObjectMapper objectMapper;

	@Autowired
	public PostgreSqlPromotionWriter(
			PostgreSqlTargetProperties properties,
			@Autowired(required = false) @Qualifier(PostgreSqlTargetClientConfig.DATASOURCE_BEAN) DataSource dataSource) {
		this(properties, dataSource, new ObjectMapper());
	}

	PostgreSqlPromotionWriter(
			PostgreSqlTargetProperties properties,
			DataSource dataSource,
			ObjectMapper objectMapper) {
		this.properties = properties;
		this.dataSource = dataSource;
		this.objectMapper = objectMapper;
	}

	public long promoteBatch(
			Map<String, Object> definitionConfiguration,
			PromotionTargetConfiguration promotion,
			UUID migrationRunId,
			Long migrationBatchId) {
		if (dataSource == null) {
			throw new TargetInfrastructureException(
					MigrationErrorCodes.TARGET_DATABASE_CONNECTION_ERROR,
					"PostgreSQL target datasource is not configured");
		}
		PostgreSqlStagingTarget.Resolved staging = PostgreSqlStagingTarget.resolve(definitionConfiguration, properties);
		String selectSql = "SELECT source_record_number, target_payload, idempotency_key FROM "
				+ PostgreSqlStagingTarget.quoted(staging)
				+ " WHERE migration_run_id = ? AND migration_batch_id = ? ORDER BY source_record_number";
		String writeSql = PostgreSqlPromotionSql.writeStatement(promotion);
		List<String> jsonKeys = PostgreSqlPromotionSql.jsonKeys(promotion);
		try (Connection connection = dataSource.getConnection()) {
			boolean previousAutoCommit = connection.getAutoCommit();
			connection.setAutoCommit(false);
			try {
				long written = 0L;
				try (PreparedStatement select = connection.prepareStatement(
						selectSql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
						PreparedStatement write = connection.prepareStatement(writeSql)) {
					select.setFetchSize(500);
					select.setObject(1, migrationRunId);
					select.setLong(2, migrationBatchId);
					try (ResultSet resultSet = select.executeQuery()) {
						while (resultSet.next()) {
							Map<String, Object> payload = readPayload(resultSet.getObject("target_payload"));
							int index = 1;
							for (String jsonKey : jsonKeys) {
								write.setObject(index++, payload.get(jsonKey));
							}
							write.executeUpdate();
							written++;
						}
					}
				}
				connection.commit();
				return written;
			} catch (SQLException | RuntimeException ex) {
				rollbackQuietly(connection);
				throw new TargetInfrastructureException(
						MigrationErrorCodes.PROMOTION_BATCH_FAILED,
						"PostgreSQL promotion batch failed",
						ex);
			} finally {
				connection.setAutoCommit(previousAutoCommit);
			}
		} catch (TargetInfrastructureException ex) {
			throw ex;
		} catch (SQLException ex) {
			throw new TargetInfrastructureException(
					MigrationErrorCodes.TARGET_DATABASE_CONNECTION_ERROR,
					"PostgreSQL promotion connection failed",
					ex);
		}
	}

	private Map<String, Object> readPayload(Object raw) {
		try {
			if (raw == null) {
				return Map.of();
			}
			if (raw instanceof Map<?, ?> map) {
				@SuppressWarnings("unchecked")
				Map<String, Object> typed = (Map<String, Object>) map;
				return typed;
			}
			String json = raw instanceof PGobject pgObject ? pgObject.getValue() : raw.toString();
			if (json == null || json.isBlank()) {
				return Map.of();
			}
			return objectMapper.readValue(json, PAYLOAD_TYPE);
		} catch (Exception ex) {
			throw new TargetInfrastructureException(
					MigrationErrorCodes.PROMOTION_BATCH_FAILED,
					"PostgreSQL promotion payload is invalid",
					ex);
		}
	}

	private static void rollbackQuietly(Connection connection) {
		try {
			connection.rollback();
		} catch (SQLException ex) {
			throw new TargetInfrastructureException(
					MigrationErrorCodes.PROMOTION_BATCH_FAILED,
					"PostgreSQL promotion rollback failed",
					ex);
		}
	}
}
