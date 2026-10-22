package com.etp.migration.connector.target;

import java.sql.SQLException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * Summarises a target failure without database message, detail or hint text, which can quote row values. Only the
 * SQLState and the structural fields PostgreSQL reports (schema, table, column, constraint, datatype) are used.
 */
public final class TargetFailureDiagnostics {

	private static final int MAX_DEPTH = 16;
	private static final Pattern MISSING_RELATION = Pattern.compile("relation \"([^\"]+)\" does not exist");
	private static final Map<String, String> CONDITIONS = Map.ofEntries(
			Map.entry("08000", "connection_exception"),
			Map.entry("08001", "sqlclient_unable_to_establish_sqlconnection"),
			Map.entry("08003", "connection_does_not_exist"),
			Map.entry("08006", "connection_failure"),
			Map.entry("22001", "string_data_right_truncation"),
			Map.entry("22003", "numeric_value_out_of_range"),
			Map.entry("22007", "invalid_datetime_format"),
			Map.entry("22008", "datetime_field_overflow"),
			Map.entry("22P02", "invalid_text_representation"),
			Map.entry("23502", "not_null_violation"),
			Map.entry("23503", "foreign_key_violation"),
			Map.entry("23505", "unique_violation"),
			Map.entry("23514", "check_violation"),
			Map.entry("23P01", "exclusion_violation"),
			Map.entry("28P01", "invalid_password"),
			Map.entry("3D000", "invalid_catalog_name"),
			Map.entry("40001", "serialization_failure"),
			Map.entry("40P01", "deadlock_detected"),
			Map.entry("42501", "insufficient_privilege"),
			Map.entry("42703", "undefined_column"),
			Map.entry("42P01", "undefined_table"),
			Map.entry("53300", "too_many_connections"),
			Map.entry("57014", "query_canceled"),
			Map.entry("57P01", "admin_shutdown"));

	private TargetFailureDiagnostics() {
	}

	public static String describe(Throwable failure) {
		if (failure == null) {
			return "unknown failure";
		}
		SQLException sql = mostSpecificSqlException(failure);
		if (sql == null) {
			return failure.getClass().getSimpleName();
		}
		String state = sql.getSQLState();
		StringBuilder summary = new StringBuilder(condition(state))
				.append(" (SQLState ").append(state == null ? "unknown" : state).append(')');
		if (sql instanceof PSQLException psql && psql.getServerErrorMessage() != null) {
			ServerErrorMessage server = psql.getServerErrorMessage();
			String table = server.getTable() == null
					? null
					: server.getSchema() == null ? server.getTable() : server.getSchema() + "." + server.getTable();
			append(summary, "table", table);
			append(summary, "column", server.getColumn());
			append(summary, "constraint", server.getConstraint());
			append(summary, "type", server.getDatatype());
		}
		return summary.toString();
	}

	public static boolean involvesDatabase(Throwable failure) {
		return failure != null && mostSpecificSqlException(failure) != null;
	}

	/**
	 * Exception class and the first application stack frame. The exception message is omitted because it can quote
	 * row values or SQL parameters.
	 */
	public static String location(Throwable failure) {
		if (failure == null) {
			return "unknown failure";
		}
		String type = failure.getClass().getName();
		StackTraceElement frame = applicationFrame(failure.getStackTrace());
		if (frame == null) {
			return type;
		}
		return type + " at " + frame;
	}

	private static StackTraceElement applicationFrame(StackTraceElement[] frames) {
		if (frames == null) {
			return null;
		}
		for (StackTraceElement frame : frames) {
			if (frame == null) {
				continue;
			}
			String className = frame.getClassName();
			if (className == null || className.isBlank()
					|| className.startsWith("java.")
					|| className.startsWith("javax.")
					|| className.startsWith("jdk.")
					|| className.startsWith("sun.")) {
				continue;
			}
			return frame;
		}
		return null;
	}

	/**
	 * Structural PostgreSQL fields only. The server detail and hint are omitted because they can quote row values.
	 * {@code relation} is set only when the primary server message is {@code relation "name" does not exist}.
	 */
	public static SqlFacts sqlFacts(Throwable failure) {
		SQLException sql = mostSpecificSqlException(failure);
		if (sql == null) {
			return null;
		}
		String schema = null;
		String table = null;
		String column = null;
		String constraint = null;
		String datatype = null;
		String relation = null;
		String serverMessage = null;
		if (sql instanceof PSQLException psql && psql.getServerErrorMessage() != null) {
			ServerErrorMessage server = psql.getServerErrorMessage();
			schema = blank(server.getSchema());
			table = blank(server.getTable());
			column = blank(server.getColumn());
			constraint = blank(server.getConstraint());
			datatype = blank(server.getDatatype());
			serverMessage = blank(server.getMessage());
			relation = missingRelation(serverMessage);
		}
		String state = blank(sql.getSQLState());
		return new SqlFacts(
				state,
				state == null ? null : condition(state),
				schema,
				table,
				column,
				constraint,
				datatype,
				relation,
				serverMessage);
	}

	public record SqlFacts(
			String sqlState,
			String condition,
			String schema,
			String table,
			String column,
			String constraint,
			String datatype,
			String relation,
			String serverMessage) {
	}

	private static SQLException mostSpecificSqlException(Throwable failure) {
		Throwable current = failure;
		for (int depth = 0; current != null && depth < MAX_DEPTH; depth++) {
			if (current instanceof SQLException sql) {
				SQLException candidate = sql;
				for (int next = 0; next < MAX_DEPTH && !hasServerMessage(candidate) && candidate.getNextException() != null; next++) {
					candidate = candidate.getNextException();
				}
				return hasServerMessage(candidate) ? candidate : sql;
			}
			current = current.getCause();
		}
		return null;
	}

	private static boolean hasServerMessage(SQLException sql) {
		return sql instanceof PSQLException psql && psql.getServerErrorMessage() != null;
	}

	private static String condition(String state) {
		if (state == null) {
			return "database_error";
		}
		String known = CONDITIONS.get(state);
		if (known != null) {
			return known;
		}
		return switch (state.length() >= 2 ? state.substring(0, 2) : state) {
			case "08" -> "connection_exception";
			case "22" -> "data_exception";
			case "23" -> "integrity_constraint_violation";
			case "42" -> "syntax_error_or_access_rule_violation";
			default -> "database_error";
		};
	}

	private static void append(StringBuilder summary, String label, String value) {
		if (value != null && !value.isBlank()) {
			summary.append(", ").append(label).append(' ').append(value);
		}
	}

	private static String blank(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value;
	}

	private static String missingRelation(String serverMessage) {
		if (serverMessage == null) {
			return null;
		}
		Matcher matcher = MISSING_RELATION.matcher(serverMessage);
		if (!matcher.matches()) {
			return null;
		}
		String name = matcher.group(1);
		return name == null || name.isBlank() ? null : name;
	}
}
