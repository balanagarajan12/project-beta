package com.etp.migration.connector.target.postgresql;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Batch promotion looks up staging rows by run and batch. The identity unique index
 * does not serve that lookup. This check does not create the index: {@code CREATE INDEX}
 * on a large staging table blocks writers, and {@code CREATE INDEX CONCURRENTLY} cannot
 * run inside the caller's transaction.
 */
public final class PostgreSqlStagingIndexes {

	private static final ConcurrentHashMap<String, Boolean> ENSURED = new ConcurrentHashMap<>();

	private PostgreSqlStagingIndexes() {
	}

	/**
	 * Creates {@code idx_uam_entity_stage_run_batch} when that staging table is in this database.
	 * The caller must not be inside a transaction. A missing table is skipped: the control plane
	 * often does not hold staging. An invalid index is left in place for an operator to drop.
	 */
	public static void createBatchLookupIfPresent(Connection connection) throws SQLException {
		if (!connection.getAutoCommit()) {
			throw new SQLException("CREATE INDEX CONCURRENTLY cannot run inside a transaction");
		}
		String table = "uam_entity_stage";
		String schema = "migration_staging";
		String index = indexName(table);
		if (!relationExists(connection, schema, table)) {
			return;
		}
		Boolean valid = indexValid(connection, schema, table, index);
		if (Boolean.TRUE.equals(valid)) {
			return;
		}
		if (Boolean.FALSE.equals(valid)) {
			throw new SQLException("Staging batch lookup index " + schema + "." + index
					+ " is invalid. Drop it outside a transaction, then create it again:"
					+ " DROP INDEX CONCURRENTLY IF EXISTS " + schema + "." + index);
		}
		try (java.sql.Statement statement = connection.createStatement()) {
			statement.execute("CREATE INDEX CONCURRENTLY IF NOT EXISTS " + index
					+ " ON " + schema + "." + table + " (migration_run_id, migration_batch_id)");
		}
	}

	private static boolean relationExists(Connection connection, String schema, String table) throws SQLException {
		String sql = """
				SELECT 1
				FROM pg_class t
				JOIN pg_namespace n ON n.oid = t.relnamespace
				WHERE n.nspname = ? AND t.relname = ? AND t.relkind = 'r'
				""";
		try (java.sql.PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, schema);
			statement.setString(2, table);
			try (java.sql.ResultSet resultSet = statement.executeQuery()) {
				return resultSet.next();
			}
		}
	}

	private static Boolean indexValid(Connection connection, String schema, String table, String index) throws SQLException {
		String sql = """
				SELECT i.indisvalid
				FROM pg_index i
				JOIN pg_class c ON c.oid = i.indexrelid
				JOIN pg_class t ON t.oid = i.indrelid
				JOIN pg_namespace n ON n.oid = t.relnamespace
				WHERE n.nspname = ? AND t.relname = ? AND c.relname = ?
				""";
		try (java.sql.PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, schema);
			statement.setString(2, table);
			statement.setString(3, index);
			try (java.sql.ResultSet resultSet = statement.executeQuery()) {
				if (!resultSet.next()) {
					return null;
				}
				return resultSet.getBoolean(1);
			}
		}
	}

	static void ensureBatchLookup(Connection connection, PostgreSqlStagingTarget.Resolved staging) throws SQLException {
		String key = connection.getMetaData().getURL() + "|" + staging.schema() + "." + staging.table();
		if (ENSURED.containsKey(key)) {
			return;
		}
		String index = indexName(staging.table());
		String sql = """
				SELECT i.indisvalid
				FROM pg_index i
				JOIN pg_class c ON c.oid = i.indexrelid
				JOIN pg_class t ON t.oid = i.indrelid
				JOIN pg_namespace n ON n.oid = t.relnamespace
				WHERE n.nspname = ? AND t.relname = ? AND c.relname = ?
				""";
		try (java.sql.PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, staging.schema());
			statement.setString(2, staging.table());
			statement.setString(3, index);
			try (java.sql.ResultSet resultSet = statement.executeQuery()) {
				if (!resultSet.next()) {
					org.slf4j.LoggerFactory.getLogger(PostgreSqlStagingIndexes.class).warn(
							"Staging batch lookup index {} is missing on {}.{}."
									+ " Create it outside a transaction while staging writers are idle:"
									+ " CREATE INDEX CONCURRENTLY IF NOT EXISTS {} ON {}.{} (migration_run_id, migration_batch_id)",
							index, staging.schema(), staging.table(), index, staging.schema(), staging.table());
				} else if (!resultSet.getBoolean(1)) {
					org.slf4j.LoggerFactory.getLogger(PostgreSqlStagingIndexes.class).error(
							"Staging batch lookup index {} on {}.{} is invalid."
									+ " Drop it outside a transaction, then create it again:"
									+ " DROP INDEX CONCURRENTLY IF EXISTS {}.{};",
							index, staging.schema(), staging.table(), staging.schema(), index);
				}
			}
		}
		ENSURED.put(key, Boolean.TRUE);
	}

	static String indexName(String table) {
		String name = "idx_" + table + "_run_batch";
		return name.length() <= 63 ? name : name.substring(0, 63);
	}
}
