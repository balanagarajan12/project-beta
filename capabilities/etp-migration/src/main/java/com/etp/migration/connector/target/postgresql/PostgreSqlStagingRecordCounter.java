package com.etp.migration.connector.target.postgresql;

import com.etp.migration.connector.target.TargetInfrastructureException;
import com.etp.migration.processing.MigrationErrorCodes;
import com.etp.migration.promotion.StagingRecordCounter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class PostgreSqlStagingRecordCounter implements StagingRecordCounter {

	/** A stuck count fails the preparation transaction instead of holding the phase worker. */
	private static final int COUNT_TIMEOUT_SECONDS = 120;

	private final PostgreSqlTargetProperties properties;
	private final DataSource dataSource;
	private final PostgreSqlConnectionRegistry connections;

	@Autowired
	public PostgreSqlStagingRecordCounter(
			PostgreSqlTargetProperties properties,
			@Autowired(required = false) @Qualifier(PostgreSqlTargetClientConfig.DATASOURCE_BEAN) DataSource dataSource,
			@Autowired(required = false) PostgreSqlConnectionRegistry connections) {
		this.properties = properties;
		this.dataSource = dataSource;
		this.connections = connections;
	}

	@Override
	public Map<Long, Long> countStagedRecordsByBatch(UUID migrationRunId, Map<String, Object> configuration) {
		DataSource source = PostgreSqlConnectionRegistry.stagingOf(connections, dataSource, configuration);
		PostgreSqlStagingTarget.Resolved staging = PostgreSqlStagingTarget.resolve(configuration, properties);
		String sql = "SELECT migration_batch_id, COUNT(*) FROM " + PostgreSqlStagingTarget.quoted(staging)
				+ " WHERE migration_run_id = ? GROUP BY migration_batch_id";
		Map<Long, Long> counts = new java.util.HashMap<>();
		try (Connection connection = source.getConnection();
				PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setQueryTimeout(COUNT_TIMEOUT_SECONDS);
			statement.setObject(1, migrationRunId);
			try (ResultSet resultSet = statement.executeQuery()) {
				while (resultSet.next()) {
					counts.put(resultSet.getLong(1), resultSet.getLong(2));
				}
			}
			return counts;
		} catch (SQLException ex) {
			throw new TargetInfrastructureException(
					MigrationErrorCodes.TARGET_DATABASE_CONNECTION_ERROR,
					"PostgreSQL staging count failed",
					ex);
		}
	}

	@Override
	public long countStagedRecords(UUID migrationRunId, Long migrationBatchId, Map<String, Object> configuration) {
		DataSource source = PostgreSqlConnectionRegistry.stagingOf(connections, dataSource, configuration);
		PostgreSqlStagingTarget.Resolved staging = PostgreSqlStagingTarget.resolve(configuration, properties);
		String sql = "SELECT COUNT(*) FROM " + PostgreSqlStagingTarget.quoted(staging)
				+ " WHERE migration_run_id = ? AND migration_batch_id = ?";
		try (Connection connection = source.getConnection();
				PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setQueryTimeout(COUNT_TIMEOUT_SECONDS);
			statement.setObject(1, migrationRunId);
			statement.setLong(2, migrationBatchId);
			try (ResultSet resultSet = statement.executeQuery()) {
				resultSet.next();
				return resultSet.getLong(1);
			}
		} catch (SQLException ex) {
			throw new TargetInfrastructureException(
					MigrationErrorCodes.TARGET_DATABASE_CONNECTION_ERROR,
					"PostgreSQL staging count failed",
					ex);
		}
	}
}
