package com.etp.migration.connector.target.uam;

import com.etp.migration.connector.target.TargetInfrastructureException;
import com.etp.migration.processing.MigrationErrorCodes;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Checks configured foreign keys against the target catalog.
 * {@code referencesColumn} must exist on the parent table. When the database already
 * declares the foreign key, that constraint's referenced column is authoritative.
 */
final class UamForeignKeySchema {

	private UamForeignKeySchema() {
	}

	static void verify(Connection connection, List<UamCanonicalRows.ForeignKeyBinding> bindings) throws SQLException {
		if (bindings == null || bindings.isEmpty()) {
			return;
		}
		for (UamCanonicalRows.ForeignKeyBinding binding : bindings) {
			if (binding.referencesColumn() == null) {
				continue;
			}
			if (!columnExists(connection, binding.schema(), binding.childTable(), binding.childColumn())) {
				throw configurationError(
						"Foreign key column '" + binding.childColumn()
								+ "' is not on table '" + binding.childTable() + "'");
			}
			if (!columnExists(connection, binding.schema(), binding.parentTable(), binding.referencesColumn())) {
				throw configurationError(
						"Referenced column '" + binding.referencesColumn()
								+ "' is not on table '" + binding.parentTable() + "'");
			}
			String actual = referencedColumn(
					connection,
					binding.schema(),
					binding.childTable(),
					binding.childColumn(),
					binding.parentTable());
			if (actual != null && !actual.equals(binding.referencesColumn())) {
				throw configurationError(
						"Foreign key '" + binding.childColumn() + "' on '" + binding.childTable()
								+ "' references '" + actual + "', not '" + binding.referencesColumn() + "'");
			}
		}
	}

	private static boolean columnExists(Connection connection, String schema, String table, String column)
			throws SQLException {
		String sql = """
				SELECT 1
				FROM information_schema.columns
				WHERE table_schema = ? AND table_name = ? AND column_name = ?
				""";
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, schema);
			statement.setString(2, table);
			statement.setString(3, column);
			try (ResultSet resultSet = statement.executeQuery()) {
				return resultSet.next();
			}
		}
	}

	private static String referencedColumn(
			Connection connection,
			String schema,
			String childTable,
			String childColumn,
			String parentTable) throws SQLException {
		String sql = """
				SELECT ccu.column_name
				FROM information_schema.table_constraints tc
				JOIN information_schema.key_column_usage kcu
				  ON tc.constraint_name = kcu.constraint_name
				 AND tc.table_schema = kcu.table_schema
				 AND tc.table_name = kcu.table_name
				JOIN information_schema.constraint_column_usage ccu
				  ON ccu.constraint_name = tc.constraint_name
				 AND ccu.constraint_schema = tc.table_schema
				WHERE tc.constraint_type = 'FOREIGN KEY'
				  AND tc.table_schema = ?
				  AND tc.table_name = ?
				  AND kcu.column_name = ?
				  AND ccu.table_name = ?
				""";
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, schema);
			statement.setString(2, childTable);
			statement.setString(3, childColumn);
			statement.setString(4, parentTable);
			try (ResultSet resultSet = statement.executeQuery()) {
				if (!resultSet.next()) {
					return null;
				}
				return resultSet.getString(1);
			}
		}
	}

	private static TargetInfrastructureException configurationError(String message) {
		return new TargetInfrastructureException(MigrationErrorCodes.TARGET_DATABASE_CONFIGURATION_ERROR, message);
	}
}
