package com.etp.migration.connector.target.postgresql;

import com.etp.migration.connector.target.MigrationTargetReconciliationProvider;
import com.etp.migration.connector.target.TargetInfrastructureException;
import com.etp.migration.connector.target.TargetVerification;
import com.etp.migration.processing.MigrationErrorCodes;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class PostgreSqlTargetReconciliationProvider implements MigrationTargetReconciliationProvider {

	private final PostgreSqlTargetProperties properties;
	private final DataSource dataSource;

	@Autowired
	public PostgreSqlTargetReconciliationProvider(
			PostgreSqlTargetProperties properties,
			@Autowired(required = false) @Qualifier(PostgreSqlTargetClientConfig.DATASOURCE_BEAN) DataSource dataSource) {
		this.properties = properties;
		this.dataSource = dataSource;
	}

	@Override
	public String targetType() {
		return PostgreSqlTargetConnector.TARGET_TYPE;
	}

	@Override
	public TargetVerification verify(UUID migrationRunId, Map<String, Object> definitionConfiguration) {
		if (dataSource == null) {
			return TargetVerification.unavailable(
					"STAGING",
					"PostgreSQL target datasource is not configured");
		}
		PostgreSqlStagingTarget.Resolved staging = PostgreSqlStagingTarget.resolve(definitionConfiguration, properties);
		long count = countStagingRows(staging, migrationRunId);
		Map<String, Object> attributes = new LinkedHashMap<>();
		attributes.put("targetSchema", staging.schema());
		attributes.put("targetTable", staging.table());
		attributes.put("mergeStrategy", staging.mergeStrategy());
		return new TargetVerification(
				true,
				count,
				"STAGING",
				false,
				"PostgreSQL mergeStrategy=NONE verifies staging ingestion only",
				attributes);
	}

	private long countStagingRows(PostgreSqlStagingTarget.Resolved staging, UUID migrationRunId) {
		String sql = "SELECT COUNT(*) FROM " + PostgreSqlStagingTarget.quoted(staging) + " WHERE migration_run_id = ?";
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setObject(1, migrationRunId);
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
