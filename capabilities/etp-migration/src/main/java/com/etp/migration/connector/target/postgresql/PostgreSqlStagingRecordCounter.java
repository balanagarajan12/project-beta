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

	private final PostgreSqlTargetProperties properties;
	private final DataSource dataSource;

	@Autowired
	public PostgreSqlStagingRecordCounter(
			PostgreSqlTargetProperties properties,
			@Autowired(required = false) @Qualifier(PostgreSqlTargetClientConfig.DATASOURCE_BEAN) DataSource dataSource) {
		this.properties = properties;
		this.dataSource = dataSource;
	}

	@Override
	public long countStagedRecords(UUID migrationRunId, Long migrationBatchId, Map<String, Object> configuration) {
		if (dataSource == null) {
			throw new TargetInfrastructureException(
					MigrationErrorCodes.TARGET_DATABASE_CONNECTION_ERROR,
					"PostgreSQL target datasource is not configured");
		}
		PostgreSqlStagingTarget.Resolved staging = PostgreSqlStagingTarget.resolve(configuration, properties);
		String sql = "SELECT COUNT(*) FROM " + PostgreSqlStagingTarget.quoted(staging)
				+ " WHERE migration_run_id = ? AND migration_batch_id = ?";
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(sql)) {
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
