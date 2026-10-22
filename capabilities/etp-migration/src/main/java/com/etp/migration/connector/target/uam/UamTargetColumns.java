package com.etp.migration.connector.target.uam;

import com.etp.migration.connector.target.TargetInfrastructureException;
import com.etp.migration.processing.MigrationErrorCodes;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.postgresql.util.PGobject;

/**
 * Reads PostgreSQL column metadata and converts canonical values to those types.
 * The catalog is authoritative. Request configuration does not supply column types.
 * A required {@code timestamp} or {@code timestamptz} column with no default receives one UTC
 * instant for the load when the mapped value is missing or blank. Other required columns with
 * no value fail before insert.
 * An unrecognized {@code udt_name}, including an array, enum, domain, or {@code bytea}, is left unchanged.
 */
final class UamTargetColumns {

	private static final ObjectMapper JSON = new ObjectMapper();

	private UamTargetColumns() {
	}

	static Map<String, Column> load(Connection connection, String schema, String table) throws SQLException {
		String sql = """
				SELECT column_name, udt_name, is_nullable, column_default,
				       is_identity, identity_generation, is_generated
				FROM information_schema.columns
				WHERE table_schema = ? AND table_name = ?
				""";
		Map<String, Column> columns = new LinkedHashMap<>();
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, schema);
			statement.setString(2, table);
			try (ResultSet resultSet = statement.executeQuery()) {
				while (resultSet.next()) {
					Column column = new Column(
							schema,
							table,
							resultSet.getString("column_name"),
							resultSet.getString("udt_name"),
							"YES".equalsIgnoreCase(resultSet.getString("is_nullable")),
							text(resultSet.getString("column_default")),
							"YES".equalsIgnoreCase(resultSet.getString("is_identity")),
							text(resultSet.getString("identity_generation")),
							text(resultSet.getString("is_generated")));
					columns.put(column.name(), column);
				}
			}
		}
		if (columns.isEmpty()) {
			throw configurationError("Target table '" + schema + "." + table + "' was not found");
		}
		return columns;
	}

	static List<UamCanonicalRows.InsertGroup> bind(
			UamCanonicalRows.InsertGroup group,
			Map<String, Column> catalog) {
		return bind(group, catalog, OffsetDateTime.now(ZoneOffset.UTC));
	}

	static List<UamCanonicalRows.InsertGroup> bind(
			UamCanonicalRows.InsertGroup group,
			Map<String, Column> catalog,
			OffsetDateTime loadedAt) {
		String insertKey = group.columns().isEmpty() ? null : group.columns().get(0);
		Map<List<String>, List<List<Object>>> grouped = new LinkedHashMap<>();
		for (List<Object> row : group.parameters()) {
			Map<String, Object> values = new LinkedHashMap<>();
			for (int index = 0; index < group.columns().size(); index++) {
				values.put(group.columns().get(index), row.get(index));
			}
			fillRequiredTimestamps(catalog, values, loadedAt);
			BoundRow bound = bindRow(group, catalog, values, insertKey);
			grouped.computeIfAbsent(bound.columns(), ignored -> new ArrayList<>()).add(bound.values());
		}
		List<UamCanonicalRows.InsertGroup> groups = new ArrayList<>();
		grouped.forEach((columns, parameters) -> groups.add(new UamCanonicalRows.InsertGroup(
				group.schema(),
				group.table(),
				columns,
				parameters)));
		return groups;
	}

	private static void fillRequiredTimestamps(
			Map<String, Column> catalog,
			Map<String, Object> values,
			OffsetDateTime loadedAt) {
		for (Column column : catalog.values()) {
			if (!column.required() || !timestampType(column)) {
				continue;
			}
			Object current = values.get(column.name());
			if (!values.containsKey(column.name()) || blank(current)) {
				values.put(column.name(), timestampFill(column, loadedAt));
			}
		}
	}

	private static boolean timestampType(Column column) {
		String type = column.udtName() == null ? "" : column.udtName().toLowerCase(Locale.ROOT);
		return "timestamptz".equals(type) || "timestamp".equals(type);
	}

	private static Object timestampFill(Column column, OffsetDateTime loadedAt) {
		if ("timestamp".equalsIgnoreCase(column.udtName())) {
			return loadedAt.toLocalDateTime();
		}
		return loadedAt;
	}

	private static BoundRow bindRow(
			UamCanonicalRows.InsertGroup group,
			Map<String, Column> catalog,
			Map<String, Object> row,
			String insertKey) {
		for (Column column : catalog.values()) {
			if (!column.required() || timestampType(column)) {
				continue;
			}
			if (!row.containsKey(column.name()) || blank(row.get(column.name()))) {
				throw configurationError(
						"Column '" + column.name() + "' on '" + column.table() + "' is required");
			}
		}
		List<String> ordered = new ArrayList<>();
		if (insertKey != null) {
			ordered.add(insertKey);
		}
		for (String name : row.keySet()) {
			if (!name.equals(insertKey)) {
				ordered.add(name);
			}
		}
		List<String> columns = new ArrayList<>();
		List<Object> values = new ArrayList<>();
		for (String name : ordered) {
			Column column = require(catalog, group, name);
			Object raw = row.get(name);
			if (column.generatedAlways()) {
				continue;
			}
			if (column.identityAlways()) {
				if (!blank(raw)) {
					throw configurationError(
							"Column '" + column.name() + "' on '" + column.table()
									+ "' is generated by the database and cannot accept a value");
				}
				continue;
			}
			if (blank(raw) && (column.identity() || column.defaultExpression() != null)) {
				continue;
			}
			Object value = coerce(column, raw);
			if (value == null && column.required()) {
				throw configurationError(
						"Column '" + column.name() + "' on '" + column.table() + "' is required");
			}
			columns.add(name);
			values.add(value);
		}
		if (columns.isEmpty()) {
			throw configurationError("Canonical insert for '" + group.table() + "' has no writable columns");
		}
		if (insertKey != null && !insertKey.equals(columns.get(0))) {
			throw configurationError(
					"Column '" + insertKey + "' on '" + group.table()
							+ "' is assigned by the database and cannot be omitted from the insert");
		}
		return new BoundRow(List.copyOf(columns), values);
	}

	static Object coerce(Column column, Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof String text) {
			String trimmed = text.trim();
			if (trimmed.isEmpty()) {
				return null;
			}
			value = trimmed;
		}
		String type = column.udtName() == null ? "" : column.udtName().toLowerCase(Locale.ROOT);
		try {
			return switch (type) {
				case "numeric", "decimal" -> decimal(value);
				case "int2", "int4" -> integer(value);
				case "int8" -> longValue(value);
				case "float4", "float8" -> doubleValue(value);
				case "bool" -> bool(value);
				case "date" -> date(value);
				case "timestamp" -> timestamp(value);
				case "timestamptz" -> timestampWithZone(value);
				case "time" -> time(value);
				case "timetz" -> timeWithZone(value);
				case "uuid" -> uuid(value);
				case "json", "jsonb" -> json(type, value);
				case "varchar", "text", "bpchar", "name", "citext" -> value.toString();
				default -> value;
			};
		} catch (TargetInfrastructureException ex) {
			throw ex;
		} catch (RuntimeException ex) {
			throw invalid(column, type);
		}
	}

	private static Column require(Map<String, Column> catalog, UamCanonicalRows.InsertGroup group, String name) {
		Column column = catalog.get(name);
		if (column == null) {
			throw configurationError("Column '" + name + "' is not on table '" + group.table() + "'");
		}
		return column;
	}

	private static BigDecimal decimal(Object value) {
		if (value instanceof BigDecimal decimal) {
			return decimal;
		}
		if (value instanceof Number number) {
			return new BigDecimal(number.toString());
		}
		try {
			return new BigDecimal(value.toString().trim());
		} catch (NumberFormatException ex) {
			throw new IllegalArgumentException(ex);
		}
	}

	private static Integer integer(Object value) {
		if (value instanceof Integer number) {
			return number;
		}
		if (value instanceof Number number) {
			return Math.toIntExact(number.longValue());
		}
		try {
			return Integer.valueOf(value.toString().trim());
		} catch (NumberFormatException ex) {
			throw new IllegalArgumentException(ex);
		}
	}

	private static Long longValue(Object value) {
		if (value instanceof Long number) {
			return number;
		}
		if (value instanceof Number number) {
			return number.longValue();
		}
		try {
			return Long.valueOf(value.toString().trim());
		} catch (NumberFormatException ex) {
			throw new IllegalArgumentException(ex);
		}
	}

	private static Double doubleValue(Object value) {
		if (value instanceof Double number) {
			return number;
		}
		if (value instanceof Number number) {
			return number.doubleValue();
		}
		try {
			return Double.valueOf(value.toString().trim());
		} catch (NumberFormatException ex) {
			throw new IllegalArgumentException(ex);
		}
	}

	private static Boolean bool(Object value) {
		if (value instanceof Boolean flag) {
			return flag;
		}
		String text = value.toString().trim().toLowerCase(Locale.ROOT);
		if ("true".equals(text) || "t".equals(text) || "1".equals(text) || "yes".equals(text)) {
			return Boolean.TRUE;
		}
		if ("false".equals(text) || "f".equals(text) || "0".equals(text) || "no".equals(text)) {
			return Boolean.FALSE;
		}
		throw new IllegalArgumentException("invalid boolean");
	}

	private static LocalDate date(Object value) {
		if (value instanceof LocalDate date) {
			return date;
		}
		if (value instanceof OffsetDateTime timestamp) {
			return timestamp.toLocalDate();
		}
		if (value instanceof LocalDateTime timestamp) {
			return timestamp.toLocalDate();
		}
		return LocalDate.parse(value.toString().trim());
	}

	private static LocalDateTime timestamp(Object value) {
		if (value instanceof LocalDateTime timestamp) {
			return timestamp;
		}
		if (value instanceof OffsetDateTime timestamp) {
			return timestamp.toLocalDateTime();
		}
		String text = value.toString().trim();
		try {
			return LocalDateTime.parse(text);
		} catch (DateTimeException ignored) {
			return OffsetDateTime.parse(text).toLocalDateTime();
		}
	}

	private static OffsetDateTime timestampWithZone(Object value) {
		if (value instanceof OffsetDateTime timestamp) {
			return timestamp;
		}
		if (value instanceof Instant instant) {
			return instant.atOffset(ZoneOffset.UTC);
		}
		if (value instanceof LocalDateTime timestamp) {
			return timestamp.atOffset(ZoneOffset.UTC);
		}
		String text = value.toString().trim();
		try {
			return OffsetDateTime.parse(text);
		} catch (DateTimeException ignored) {
			try {
				return Instant.parse(text).atOffset(ZoneOffset.UTC);
			} catch (DateTimeException ex) {
				return LocalDateTime.parse(text).atOffset(ZoneOffset.UTC);
			}
		}
	}

	private static LocalTime time(Object value) {
		if (value instanceof LocalTime time) {
			return time;
		}
		return LocalTime.parse(value.toString().trim());
	}

	private static OffsetTime timeWithZone(Object value) {
		if (value instanceof OffsetTime time) {
			return time;
		}
		return OffsetTime.parse(value.toString().trim());
	}

	private static UUID uuid(Object value) {
		if (value instanceof UUID id) {
			return id;
		}
		return UUID.fromString(value.toString().trim());
	}

	private static PGobject json(String type, Object value) {
		if (value instanceof PGobject object && type.equalsIgnoreCase(object.getType())) {
			return object;
		}
		PGobject object = new PGobject();
		object.setType(type);
		try {
			object.setValue(jsonText(value));
		} catch (SQLException | JsonProcessingException ex) {
			throw new IllegalArgumentException(ex);
		}
		return object;
	}

	private static String jsonText(Object value) throws JsonProcessingException {
		if (value instanceof Map<?, ?> || value instanceof List<?>) {
			return JSON.writeValueAsString(value);
		}
		return value.toString();
	}

	private static boolean blank(Object value) {
		if (value == null) {
			return true;
		}
		return value instanceof String text && text.isBlank();
	}

	private static String text(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value.trim();
	}

	private static TargetInfrastructureException invalid(Column column, String type) {
		return configurationError(
				"Value for column '" + column.name() + "' on '" + column.table()
						+ "' is not a valid " + type);
	}

	private static TargetInfrastructureException configurationError(String message) {
		return new TargetInfrastructureException(MigrationErrorCodes.TARGET_DATABASE_CONFIGURATION_ERROR, message);
	}

	record Column(
			String schema,
			String table,
			String name,
			String udtName,
			boolean nullable,
			String defaultExpression,
			boolean identity,
			String identityGeneration,
			String generated) {

		boolean generatedAlways() {
			return generated != null && "ALWAYS".equalsIgnoreCase(generated);
		}

		boolean identityAlways() {
			return identity && identityGeneration != null && "ALWAYS".equalsIgnoreCase(identityGeneration);
		}

		boolean required() {
			return !nullable && defaultExpression == null && !identity && !generatedAlways();
		}
	}

	private record BoundRow(List<String> columns, List<Object> values) {
	}
}
