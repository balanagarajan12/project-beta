package com.etp.migration.connector.target.uam;

import com.etp.migration.connector.target.LoadableTargetRecord;
import com.etp.migration.connector.target.MigrationTargetConnector;
import com.etp.migration.connector.target.MigrationTargetContext;
import com.etp.migration.connector.target.TargetInfrastructureException;
import com.etp.migration.connector.target.TargetLoadModeNotSupportedException;
import com.etp.migration.connector.target.TargetLoadResult;
import com.etp.migration.connector.target.postgresql.PostgreSqlTargetClientConfig;
import com.etp.migration.entity.LoadMode;
import com.etp.migration.processing.MigrationErrorCodes;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Inserts entity payloads into configured canonical tables.
 * Staging loads stay on {@code PostgreSqlTargetConnector}. This connector runs only when the
 * definition target type is {@code UAM_POSTGRESQL} and {@code target.mode} is {@code UAM}.
 * One batch is one database transaction. A failure rolls the batch back.
 * Rows already stored under the same primary key are left in place so a retry does not duplicate them.
 */
@Component
public class UamPostgreSqlTargetConnector implements MigrationTargetConnector {

	static final String TARGET_TYPE = "UAM_POSTGRESQL";
	private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
	private static final int BATCH_SIZE = 100;

	private final DataSource dataSource;

	@Autowired
	public UamPostgreSqlTargetConnector(
			@Autowired(required = false) @Qualifier(PostgreSqlTargetClientConfig.DATASOURCE_BEAN) DataSource dataSource) {
		this.dataSource = dataSource;
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
		int submitted = records == null ? 0 : records.size();
		if (submitted == 0) {
			return new TargetLoadResult(0, 0, 0, List.of(), List.of());
		}
		if (dataSource == null) {
			throw new TargetInfrastructureException(
					MigrationErrorCodes.TARGET_DATABASE_CONFIGURATION_ERROR,
					"PostgreSQL target datasource is not configured");
		}
		List<UamCanonicalRows.InsertGroup> groups = UamCanonicalRows.plan(context.configuration(), records);
		try (Connection connection = dataSource.getConnection()) {
			boolean previousAutoCommit = connection.getAutoCommit();
			connection.setAutoCommit(false);
			try {
				for (UamCanonicalRows.InsertGroup group : groups) {
					insert(connection, group);
				}
				connection.commit();
			} catch (SQLException | RuntimeException ex) {
				rollback(connection);
				if (ex instanceof TargetInfrastructureException infrastructure) {
					throw infrastructure;
				}
				throw new TargetInfrastructureException(
						MigrationErrorCodes.TARGET_COPY_FAILED,
						"Canonical target load failed and was rolled back",
						ex);
			} finally {
				connection.setAutoCommit(previousAutoCommit);
			}
		} catch (TargetInfrastructureException ex) {
			throw ex;
		} catch (SQLException ex) {
			throw new TargetInfrastructureException(
					MigrationErrorCodes.TARGET_DATABASE_CONNECTION_ERROR,
					"PostgreSQL target connection failed",
					ex);
		}
		return new TargetLoadResult(submitted, submitted, 0, List.of(), List.of());
	}

	private static void insert(Connection connection, UamCanonicalRows.InsertGroup group) throws SQLException {
		String sql = "INSERT INTO " + quoted(group.schema()) + "." + quoted(group.table())
				+ " (" + quotedColumns(group.columns()) + ") VALUES (" + placeholders(group.columns().size()) + ")"
				+ " ON CONFLICT (" + quoted(group.columns().get(0)) + ") DO NOTHING";
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			int pending = 0;
			for (List<Object> parameters : group.parameters()) {
				for (int index = 0; index < parameters.size(); index++) {
					Object value = parameters.get(index);
					if (value == null) {
						statement.setObject(index + 1, null);
					} else {
						statement.setObject(index + 1, value);
					}
				}
				statement.addBatch();
				pending++;
				if (pending == BATCH_SIZE) {
					statement.executeBatch();
					pending = 0;
				}
			}
			if (pending > 0) {
				statement.executeBatch();
			}
		}
	}

	private static void rollback(Connection connection) {
		try {
			connection.rollback();
		} catch (SQLException ignored) {
			// The original failure is reported to the caller.
		}
	}

	private static String quotedColumns(List<String> columns) {
		StringBuilder builder = new StringBuilder();
		for (String column : columns) {
			if (!builder.isEmpty()) {
				builder.append(", ");
			}
			builder.append(quoted(column));
		}
		return builder.toString();
	}

	private static String placeholders(int count) {
		StringBuilder builder = new StringBuilder();
		for (int index = 0; index < count; index++) {
			if (index > 0) {
				builder.append(", ");
			}
			builder.append('?');
		}
		return builder.toString();
	}

	private static String quoted(String identifier) {
		if (identifier == null || !IDENTIFIER.matcher(identifier).matches()) {
			throw new TargetInfrastructureException(
					MigrationErrorCodes.TARGET_DATABASE_CONFIGURATION_ERROR,
					"Canonical identifier is invalid");
		}
		return "\"" + identifier + "\"";
	}
}
