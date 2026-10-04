package com.etp.migration.connector.target.uam;

import com.etp.migration.connector.target.LoadableTargetRecord;
import com.etp.migration.connector.target.TargetInfrastructureException;
import com.etp.migration.processing.MigrationErrorCodes;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns entity payloads into ordered canonical inserts.
 *
 * Column names come from the payload and from this connector's configuration:
 * the id column is the payload {@code targetKey}, attributes are the mapped target fields,
 * foreign keys are {@code foreignKeys}, {@code loadTimestampColumns} fills a blank column
 * with one load timestamp for the batch, and {@code columnTypes} binds numeric and timestamp
 * values to the database types. A non-blank mapped timestamp is kept when it parses.
 * Parent ids are the configured parent target identities.
 */
final class UamCanonicalRows {

	static final String MODE = "UAM";
	private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

	private UamCanonicalRows() {
	}

	static List<InsertGroup> plan(Map<String, Object> configuration, List<LoadableTargetRecord> records) {
		Settings settings = settings(configuration);
		OffsetDateTime loadedAt = OffsetDateTime.now(ZoneOffset.UTC);
		Map<String, List<PreparedRow>> byEntity = new LinkedHashMap<>();
		if (records != null) {
			for (LoadableTargetRecord record : records) {
				PreparedRow row = row(settings, record, loadedAt);
				byEntity.computeIfAbsent(row.entityName(), ignored -> new ArrayList<>()).add(row);
			}
		}
		List<InsertGroup> groups = new ArrayList<>();
		for (String entityName : settings.loadOrder()) {
			List<PreparedRow> rows = byEntity.remove(entityName);
			if (rows == null || rows.isEmpty()) {
				continue;
			}
			groups.addAll(group(settings.schema(), rows));
		}
		if (!byEntity.isEmpty()) {
			throw configurationError("Canonical loadOrder does not include every entity in the batch");
		}
		return List.copyOf(groups);
	}

	private static List<InsertGroup> group(String schema, List<PreparedRow> rows) {
		Map<String, InsertGroup> groups = new LinkedHashMap<>();
		for (PreparedRow row : rows) {
			String signature = row.table() + "\u0000" + String.join("\u0000", row.values().keySet());
			InsertGroup group = groups.get(signature);
			if (group == null) {
				group = new InsertGroup(schema, row.table(), List.copyOf(row.values().keySet()), new ArrayList<>());
				groups.put(signature, group);
			}
			group.parameters().add(List.copyOf(row.values().values()));
		}
		return List.copyOf(groups.values());
	}

	private static PreparedRow row(Settings settings, LoadableTargetRecord record, OffsetDateTime loadedAt) {
		if (record == null || record.targetRecord() == null || record.targetRecord().fields() == null) {
			throw configurationError("Canonical target payload is required");
		}
		Map<String, Object> payload = record.targetRecord().fields();
		String entityName = requiredText(payload.get("entity"), "Canonical payload entity is required");
		String table = settings.tables().get(entityName);
		if (table == null) {
			throw configurationError("Canonical table is not configured for entity '" + entityName + "'");
		}
		String idColumn = requiredText(payload.get("targetKey"), "Canonical payload targetKey is required");
		requireIdentifier(idColumn, "Canonical target key is invalid");
		String targetIdentity = requiredText(payload.get("targetIdentity"), "Canonical payload targetIdentity is required");
		Map<String, Object> values = new LinkedHashMap<>();
		values.put(idColumn, targetIdentity);
		Object attributes = payload.get("attributes");
		if (attributes instanceof Map<?, ?> attributeMap) {
			for (Map.Entry<?, ?> entry : attributeMap.entrySet()) {
				if (entry.getKey() == null) {
					continue;
				}
				String column = entry.getKey().toString();
				if (column.isBlank() || column.equals(idColumn)) {
					continue;
				}
				requireIdentifier(column, "Canonical attribute column is invalid");
				values.put(column, entry.getValue());
			}
		}
		for (ForeignKey foreignKey : settings.foreignKeys()) {
			if (!entityName.equals(foreignKey.entity())) {
				continue;
			}
			values.put(foreignKey.column(), parentTarget(payload, entityName, foreignKey.parent()));
		}
		for (LoadTimestamp loadTimestamp : settings.loadTimestamps()) {
			if (!table.equals(loadTimestamp.table())) {
				continue;
			}
			Object current = values.get(loadTimestamp.column());
			if (current == null || (current instanceof String text && text.isBlank())) {
				values.put(loadTimestamp.column(), loadedAt);
			}
		}
		for (ColumnType columnType : settings.columnTypes()) {
			if (!table.equals(columnType.table()) || !values.containsKey(columnType.column())) {
				continue;
			}
			values.put(columnType.column(), typed(columnType.type(), values.get(columnType.column())));
		}
		return new PreparedRow(entityName, table, values);
	}

	private static Object typed(String type, Object value) {
		if (value == null) {
			return null;
		}
		if ("numeric".equals(type)) {
			if (value instanceof BigDecimal decimal) {
				return decimal;
			}
			try {
				return new BigDecimal(value.toString().trim());
			} catch (NumberFormatException ex) {
				throw configurationError("Canonical numeric value is invalid");
			}
		}
		if ("timestamptz".equals(type)) {
			if (value instanceof OffsetDateTime timestamp) {
				return timestamp;
			}
			if (value instanceof Instant instant) {
				return instant.atOffset(ZoneOffset.UTC);
			}
			String text = value.toString().trim();
			try {
				return OffsetDateTime.parse(text);
			} catch (DateTimeException ignored) {
				try {
					return Instant.parse(text).atOffset(ZoneOffset.UTC);
				} catch (DateTimeException ex) {
					throw configurationError("Canonical timestamp value is invalid");
				}
			}
		}
		throw configurationError("Canonical column type is invalid");
	}

	private static String parentTarget(Map<String, Object> payload, String entityName, String parentEntity) {
		Object relationships = payload.get("relationships");
		if (relationships instanceof List<?> links) {
			for (Object link : links) {
				if (!(link instanceof Map<?, ?> relationship)) {
					continue;
				}
				if (!parentEntity.equals(text(relationship.get("parentEntity")))) {
					continue;
				}
				String targetIdentity = text(relationship.get("parentTargetIdentity"));
				if (targetIdentity != null) {
					return targetIdentity;
				}
			}
		}
		throw configurationError(
				"Canonical foreign key parent '" + parentEntity + "' was not resolved for entity '" + entityName + "'");
	}

	private static Settings settings(Map<String, Object> configuration) {
		if (configuration == null) {
			throw configurationError("Canonical target configuration is required");
		}
		Object targetValue = configuration.get("target");
		if (!(targetValue instanceof Map<?, ?> target)) {
			throw configurationError("Canonical target configuration is required");
		}
		if (!MODE.equals(text(target.get("mode")))) {
			throw configurationError("Canonical target mode must be UAM");
		}
		String schema = requiredText(target.get("schema"), "Canonical target schema is required");
		requireIdentifier(schema, "Canonical target schema is invalid");
		Map<String, String> tables = identifiers(targetMap(configuration.get("tables")), "Canonical table");
		if (tables.isEmpty()) {
			throw configurationError("Canonical tables are required");
		}
		List<String> loadOrder = stringList(configuration.get("loadOrder"));
		if (loadOrder.isEmpty()) {
			throw configurationError("Canonical loadOrder is required");
		}
		return new Settings(
				schema,
				tables,
				loadOrder,
				foreignKeys(configuration.get("foreignKeys")),
				loadTimestamps(configuration.get("loadTimestampColumns")),
				columnTypes(configuration.get("columnTypes")));
	}

	private static List<ColumnType> columnTypes(Object value) {
		if (value == null) {
			return List.of();
		}
		if (!(value instanceof List<?> list)) {
			throw configurationError("Canonical columnTypes must be a list");
		}
		List<ColumnType> types = new ArrayList<>();
		for (Object item : list) {
			if (!(item instanceof Map<?, ?> raw)) {
				throw configurationError("Canonical column type must be an object");
			}
			String table = requiredText(raw.get("table"), "Canonical column type table is required");
			String column = requiredText(raw.get("column"), "Canonical column type column is required");
			String type = requiredText(raw.get("type"), "Canonical column type is required");
			requireIdentifier(table, "Canonical column type table is invalid");
			requireIdentifier(column, "Canonical column type column is invalid");
			if (!"numeric".equals(type) && !"timestamptz".equals(type)) {
				throw configurationError("Canonical column type is invalid");
			}
			types.add(new ColumnType(table, column, type));
		}
		return List.copyOf(types);
	}

	private static List<LoadTimestamp> loadTimestamps(Object value) {
		if (value == null) {
			return List.of();
		}
		if (!(value instanceof List<?> list)) {
			throw configurationError("Canonical loadTimestampColumns must be a list");
		}
		List<LoadTimestamp> timestamps = new ArrayList<>();
		for (Object item : list) {
			if (!(item instanceof Map<?, ?> raw)) {
				throw configurationError("Canonical load timestamp must be an object");
			}
			String table = requiredText(raw.get("table"), "Canonical load timestamp table is required");
			String column = requiredText(raw.get("column"), "Canonical load timestamp column is required");
			requireIdentifier(table, "Canonical load timestamp table is invalid");
			requireIdentifier(column, "Canonical load timestamp column is invalid");
			timestamps.add(new LoadTimestamp(table, column));
		}
		return List.copyOf(timestamps);
	}

	private static List<ForeignKey> foreignKeys(Object value) {
		if (value == null) {
			return List.of();
		}
		if (!(value instanceof List<?> list)) {
			throw configurationError("Canonical foreignKeys must be a list");
		}
		List<ForeignKey> foreignKeys = new ArrayList<>();
		for (Object item : list) {
			if (!(item instanceof Map<?, ?> raw)) {
				throw configurationError("Canonical foreign key must be an object");
			}
			String entity = requiredText(raw.get("entity"), "Canonical foreign key entity is required");
			String parent = requiredText(raw.get("parent"), "Canonical foreign key parent is required");
			String column = requiredText(raw.get("column"), "Canonical foreign key column is required");
			requireIdentifier(column, "Canonical foreign key column is invalid");
			foreignKeys.add(new ForeignKey(entity, parent, column));
		}
		return List.copyOf(foreignKeys);
	}

	private static Map<String, String> identifiers(Map<String, String> values, String label) {
		Map<String, String> checked = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : values.entrySet()) {
			requireIdentifier(entry.getValue(), label + " is invalid");
			checked.put(entry.getKey(), entry.getValue());
		}
		return Map.copyOf(checked);
	}

	private static Map<String, String> targetMap(Object value) {
		if (!(value instanceof Map<?, ?> raw)) {
			return Map.of();
		}
		Map<String, String> tables = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : raw.entrySet()) {
			if (entry.getKey() == null || entry.getValue() == null) {
				continue;
			}
			String entity = entry.getKey().toString().trim();
			String table = entry.getValue().toString().trim();
			if (!entity.isEmpty() && !table.isEmpty()) {
				tables.put(entity, table);
			}
		}
		return tables;
	}

	private static List<String> stringList(Object value) {
		if (!(value instanceof List<?> list)) {
			return List.of();
		}
		List<String> values = new ArrayList<>();
		for (Object item : list) {
			if (item != null && !item.toString().isBlank()) {
				values.add(item.toString().trim());
			}
		}
		return List.copyOf(values);
	}

	private static void requireIdentifier(String value, String message) {
		if (value == null || !IDENTIFIER.matcher(value).matches()) {
			throw configurationError(message);
		}
	}

	private static String requiredText(Object value, String message) {
		String text = text(value);
		if (text == null) {
			throw configurationError(message);
		}
		return text;
	}

	private static String text(Object value) {
		if (value == null || value.toString().isBlank()) {
			return null;
		}
		return value.toString().trim();
	}

	private static TargetInfrastructureException configurationError(String message) {
		return new TargetInfrastructureException(MigrationErrorCodes.TARGET_DATABASE_CONFIGURATION_ERROR, message);
	}

	record InsertGroup(String schema, String table, List<String> columns, List<List<Object>> parameters) {
	}

	private record PreparedRow(String entityName, String table, Map<String, Object> values) {
	}

	private record Settings(
			String schema,
			Map<String, String> tables,
			List<String> loadOrder,
			List<ForeignKey> foreignKeys,
			List<LoadTimestamp> loadTimestamps,
			List<ColumnType> columnTypes) {
	}

	private record ForeignKey(String entity, String parent, String column) {
	}

	private record LoadTimestamp(String table, String column) {
	}

	private record ColumnType(String table, String column, String type) {
	}
}
