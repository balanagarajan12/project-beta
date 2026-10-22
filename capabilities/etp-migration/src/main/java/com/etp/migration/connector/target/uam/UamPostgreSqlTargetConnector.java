package com.etp.migration.connector.target.uam;

import com.etp.migration.connector.target.LoadableTargetRecord;
import com.etp.migration.connector.target.MigrationTargetConnector;
import com.etp.migration.connector.target.MigrationTargetContext;
import com.etp.migration.connector.target.TargetFailureDiagnostics;
import com.etp.migration.connector.target.TargetInfrastructureException;
import com.etp.migration.connector.target.TargetLoadModeNotSupportedException;
import com.etp.migration.connector.target.TargetLoadResult;
import com.etp.migration.connector.target.postgresql.PostgreSqlTargetClientConfig;
import com.etp.migration.entity.LoadMode;
import com.etp.migration.processing.MigrationErrorCodes;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
	private static final int BATCH_SIZE = 500;
	private final java.util.concurrent.ConcurrentHashMap<String, Map<String, UamTargetColumns.Column>> columnCatalogs =
			new java.util.concurrent.ConcurrentHashMap<>();
	private final java.util.concurrent.ConcurrentHashMap<String, Boolean> verifiedForeignKeys =
			new java.util.concurrent.ConcurrentHashMap<>();

	private final DataSource dataSource;
	private final com.etp.migration.connector.target.postgresql.PostgreSqlConnectionRegistry connections;

	@Autowired
	public UamPostgreSqlTargetConnector(
			@Autowired(required = false) @Qualifier(PostgreSqlTargetClientConfig.DATASOURCE_BEAN) DataSource dataSource,
			@Autowired(required = false) com.etp.migration.connector.target.postgresql.PostgreSqlConnectionRegistry connections) {
		this.dataSource = dataSource;
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
		int submitted = records == null ? 0 : records.size();
		if (submitted == 0) {
			return new TargetLoadResult(0, 0, 0, List.of(), List.of());
		}
		DataSource source = com.etp.migration.connector.target.postgresql.PostgreSqlConnectionRegistry.targetOf(
				connections, dataSource, context.configuration());
		UamCanonicalRows.PlannedLoad planned = UamCanonicalRows.planDetailed(context.configuration(), records);
		long started = com.etp.migration.processing.PipelineTiming.start();
		try (Connection connection = source.getConnection()) {
			verifyForeignKeys(connection, planned.foreignKeys());
			List<UamCanonicalRows.InsertGroup> groups = bind(
					connection,
					planned.groups(),
					OffsetDateTime.now(ZoneOffset.UTC));
			boolean previousAutoCommit = connection.getAutoCommit();
			connection.setAutoCommit(false);
			try {
				verifyParentsExist(connection, groups, planned.foreignKeys());
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
						"Canonical target load failed and was rolled back: " + TargetFailureDiagnostics.describe(ex),
						ex);
			} finally {
				connection.setAutoCommit(previousAutoCommit);
			}
		} catch (TargetInfrastructureException ex) {
			throw ex;
		} catch (SQLException ex) {
			throw new TargetInfrastructureException(
					MigrationErrorCodes.TARGET_DATABASE_CONNECTION_ERROR,
					"PostgreSQL target connection failed: " + TargetFailureDiagnostics.describe(ex),
					ex);
		} finally {
			com.etp.migration.processing.PipelineTiming.stop(
					com.etp.migration.processing.PipelineTiming.Stage.TARGET_LOAD, started);
		}
		return new TargetLoadResult(submitted, submitted, 0, List.of(), List.of());
	}

	private void verifyForeignKeys(Connection connection, List<UamCanonicalRows.ForeignKeyBinding> bindings)
			throws SQLException {
		if (bindings == null || bindings.isEmpty()) {
			return;
		}
		List<UamCanonicalRows.ForeignKeyBinding> unseen = new ArrayList<>();
		for (UamCanonicalRows.ForeignKeyBinding binding : bindings) {
			if (!verifiedForeignKeys.containsKey(foreignKeyKey(binding))) {
				unseen.add(binding);
			}
		}
		if (unseen.isEmpty()) {
			return;
		}
		UamForeignKeySchema.verify(connection, unseen);
		for (UamCanonicalRows.ForeignKeyBinding binding : unseen) {
			verifiedForeignKeys.put(foreignKeyKey(binding), Boolean.TRUE);
		}
	}

	private static String foreignKeyKey(UamCanonicalRows.ForeignKeyBinding binding) {
		return binding.schema() + "\u0000" + binding.childTable() + "\u0000" + binding.childColumn()
				+ "\u0000" + binding.parentTable() + "\u0000" + binding.referencesColumn();
	}

	private List<UamCanonicalRows.InsertGroup> bind(
			Connection connection,
			List<UamCanonicalRows.InsertGroup> groups,
			OffsetDateTime loadedAt) throws SQLException {
		Map<String, Map<String, UamTargetColumns.Column>> catalogs = new HashMap<>();
		List<UamCanonicalRows.InsertGroup> bound = new ArrayList<>();
		for (UamCanonicalRows.InsertGroup group : groups) {
			String key = group.schema() + "\u0000" + group.table();
			Map<String, UamTargetColumns.Column> catalog = catalogs.get(key);
			if (catalog == null) {
				catalog = columnCatalogs.get(key);
			}
			if (catalog == null) {
				catalog = UamTargetColumns.load(connection, group.schema(), group.table());
				columnCatalogs.put(key, catalog);
				catalogs.put(key, catalog);
			}
			bound.addAll(UamTargetColumns.bind(group, catalog, loadedAt));
		}
		return bound;
	}

	private static void verifyParentsExist(
			Connection connection,
			List<UamCanonicalRows.InsertGroup> groups,
			List<UamCanonicalRows.ForeignKeyBinding> bindings) throws SQLException {
		if (bindings == null || bindings.isEmpty()) {
			return;
		}
		for (UamCanonicalRows.ForeignKeyBinding binding : bindings) {
			UamCanonicalRows.InsertGroup child = null;
			for (UamCanonicalRows.InsertGroup group : groups) {
				if (group.schema().equals(binding.schema()) && group.table().equals(binding.childTable())) {
					child = group;
					break;
				}
			}
			if (child == null) {
				continue;
			}
			int column = child.columns().indexOf(binding.childColumn());
			if (column < 0) {
				continue;
			}
			List<String> referenced = new ArrayList<>();
			for (List<Object> row : child.parameters()) {
				Object value = column < row.size() ? row.get(column) : null;
				if (value != null) {
					referenced.add(String.valueOf(value));
				}
			}
			java.util.Set<String> planned = new java.util.HashSet<>();
			for (UamCanonicalRows.InsertGroup group : groups) {
				if (!group.schema().equals(binding.schema()) || !group.table().equals(binding.parentTable())) {
					continue;
				}
				int referencedColumn = group.columns().indexOf(binding.referencesColumn());
				if (referencedColumn < 0) {
					continue;
				}
				for (List<Object> row : group.parameters()) {
					if (referencedColumn < row.size() && row.get(referencedColumn) != null) {
						planned.add(String.valueOf(row.get(referencedColumn)));
					}
				}
			}
			List<String> unresolved = UamParentExistence.unresolved(planned, referenced);
			java.util.Set<String> missing = new java.util.LinkedHashSet<>(unresolved);
			for (int offset = 0; offset < unresolved.size(); offset += BATCH_SIZE) {
				List<String> chunk = unresolved.subList(offset, Math.min(offset + BATCH_SIZE, unresolved.size()));
				String sql = "SELECT " + quoted(binding.referencesColumn()) + " FROM "
						+ quoted(binding.schema()) + "." + quoted(binding.parentTable())
						+ " WHERE " + quoted(binding.referencesColumn()) + " IN (" + placeholders(chunk.size()) + ")";
				try (PreparedStatement statement = connection.prepareStatement(sql)) {
					for (int index = 0; index < chunk.size(); index++) {
						statement.setString(index + 1, chunk.get(index));
					}
					try (java.sql.ResultSet resultSet = statement.executeQuery()) {
						while (resultSet.next()) {
							missing.remove(resultSet.getString(1));
						}
					}
				}
			}
			if (!missing.isEmpty()) {
				throw new TargetInfrastructureException(
						MigrationErrorCodes.PROMOTION_BATCH_FAILED,
						"Canonical parent row is not in the target for " + binding.childTable()
								+ "." + binding.childColumn() + " referencing " + binding.parentTable()
								+ "." + binding.referencesColumn() + ": " + missing.iterator().next());
			}
		}
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
					com.etp.migration.processing.PipelineTiming.increment("targetInsertBatches");
					pending = 0;
				}
			}
			if (pending > 0) {
				statement.executeBatch();
				com.etp.migration.processing.PipelineTiming.increment("targetInsertBatches");
			}
			com.etp.migration.processing.PipelineTiming.add("targetInsertRows", group.parameters().size());
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
