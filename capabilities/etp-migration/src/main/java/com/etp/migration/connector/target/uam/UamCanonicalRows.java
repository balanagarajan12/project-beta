package com.etp.migration.connector.target.uam;

import com.etp.migration.connector.target.LoadableTargetRecord;
import com.etp.migration.connector.target.TargetInfrastructureException;
import com.etp.migration.mapping.EntityLoadOrder;
import com.etp.migration.mapping.InvalidEntityRelationshipConfigurationException;
import com.etp.migration.processing.MigrationErrorCodes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns entity payloads into ordered canonical inserts.
 *
 * The physical table is the payload {@code targetEntity}. The id column is {@code targetKey}.
 * Attributes are the mapped target fields. Foreign keys come from each staged relationship
 * {@code targetForeignKey}. Load order is the relationship graph, parents before children.
 * Column types and required timestamps are applied later from the target catalog.
 */
final class UamCanonicalRows {

	static final String MODE = "UAM";
	private static final Logger log = LoggerFactory.getLogger(UamCanonicalRows.class);
	private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

	private UamCanonicalRows() {
	}

	static List<InsertGroup> plan(Map<String, Object> configuration, List<LoadableTargetRecord> records) {
		return planDetailed(configuration, records).groups();
	}

	static PlannedLoad planDetailed(Map<String, Object> configuration, List<LoadableTargetRecord> records) {
		String schema = schema(configuration);
		List<MutableRow> rows = new ArrayList<>();
		Map<String, String> tables = new LinkedHashMap<>();
		if (records != null) {
			for (LoadableTargetRecord record : records) {
				MutableRow row = baseRow(record);
				String existing = tables.putIfAbsent(row.entityName(), row.table());
				if (existing != null && !existing.equals(row.table())) {
					throw configurationError(
							"Entity '" + row.entityName() + "' maps to more than one target table");
				}
				rows.add(row);
			}
		}
		List<ForeignKey> foreignKeys = foreignKeysFromRecords(records);
		List<String> loadOrder = derivedOrder(rows, foreignKeys);
		Map<String, MutableRow> byIdentity = new LinkedHashMap<>();
		Map<String, List<MutableRow>> byEntity = new LinkedHashMap<>();
		Map<String, String> targetKeys = new LinkedHashMap<>();
		for (MutableRow row : rows) {
			byIdentity.put(row.entityName() + "\u0000" + row.targetIdentity(), row);
			byEntity.computeIfAbsent(row.entityName(), ignored -> new ArrayList<>()).add(row);
			targetKeys.putIfAbsent(row.entityName(), row.targetKey());
		}
		List<InsertGroup> groups = new ArrayList<>();
		for (String entityName : loadOrder) {
			List<MutableRow> entityRows = byEntity.remove(entityName);
			if (entityRows == null || entityRows.isEmpty()) {
				continue;
			}
			List<PreparedRow> prepared = new ArrayList<>();
			for (MutableRow row : entityRows) {
				applyForeignKeys(row, foreignKeys, byIdentity, targetKeys);
				prepared.add(new PreparedRow(row.entityName(), row.table(), row.values()));
			}
			groups.addAll(group(schema, prepared));
		}
		if (!byEntity.isEmpty()) {
			throw configurationError("Canonical load order does not include every entity in the batch");
		}
		return new PlannedLoad(List.copyOf(groups), bindings(schema, tables, foreignKeys));
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

	private static MutableRow baseRow(LoadableTargetRecord record) {
		if (record == null || record.targetRecord() == null || record.targetRecord().fields() == null) {
			throw configurationError("Canonical target payload is required");
		}
		Map<String, Object> payload = record.targetRecord().fields();
		String entityName = requiredText(payload.get("entity"), "Canonical payload entity is required");
		String table = requiredText(payload.get("targetEntity"), "Canonical payload targetEntity is required");
		requireIdentifier(table, "Canonical target entity is invalid");
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
		return new MutableRow(entityName, table, idColumn, targetIdentity, payload, values);
	}

	private static void applyForeignKeys(
			MutableRow row,
			List<ForeignKey> foreignKeys,
			Map<String, MutableRow> byIdentity,
			Map<String, String> targetKeys) {
		for (ForeignKey foreignKey : foreignKeys) {
			if (!row.entityName().equals(foreignKey.entity())) {
				continue;
			}
			row.values().put(foreignKey.column(), referencedValue(row, foreignKey, byIdentity, targetKeys));
		}
	}

	private static Object referencedValue(
			MutableRow row,
			ForeignKey foreignKey,
			Map<String, MutableRow> byIdentity,
			Map<String, String> targetKeys) {
		String parentIdentity = parentTarget(row.payload(), row.entityName(), foreignKey.parent());
		MutableRow parent = byIdentity.get(foreignKey.parent() + "\u0000" + parentIdentity);
		if (parent == null) {
			String identityColumn = firstText(foreignKey.parentTargetKey(), targetKeys.get(foreignKey.parent()));
			if (identityColumn != null && identityColumn.equals(foreignKey.referencesColumn())) {
				log.warn(
						"Canonical parent '{}' for entity '{}' is not in this batch; copying identity column '{}'",
						foreignKey.parent(),
						row.entityName(),
						foreignKey.referencesColumn());
				return parentIdentity;
			}
			throw configurationError(
					"Canonical foreign key parent '" + foreignKey.parent()
							+ "' was not resolved for entity '" + row.entityName() + "'");
		}
		if (!parent.values().containsKey(foreignKey.referencesColumn())) {
			throw configurationError(
					"Referenced column '" + foreignKey.referencesColumn()
							+ "' is not available on parent '" + foreignKey.parent() + "'");
		}
		Object value = parent.values().get(foreignKey.referencesColumn());
		if (value == null || (value instanceof String text && text.isBlank())) {
			throw configurationError(
					"Referenced column '" + foreignKey.referencesColumn()
							+ "' has no value on parent '" + foreignKey.parent() + "'");
		}
		return value;
	}

	private static List<String> derivedOrder(List<MutableRow> rows, List<ForeignKey> foreignKeys) {
		LinkedHashSet<String> names = new LinkedHashSet<>();
		for (MutableRow row : rows) {
			names.add(row.entityName());
		}
		List<EntityLoadOrder.Dependency> dependencies = new ArrayList<>();
		for (ForeignKey foreignKey : foreignKeys) {
			names.add(foreignKey.parent());
			names.add(foreignKey.entity());
			dependencies.add(new EntityLoadOrder.Dependency(foreignKey.parent(), foreignKey.entity()));
		}
		try {
			return EntityLoadOrder.parentsFirst(names, dependencies);
		} catch (InvalidEntityRelationshipConfigurationException ex) {
			throw configurationError(ex.getMessage());
		}
	}

	private static List<ForeignKeyBinding> bindings(
			String schema,
			Map<String, String> tables,
			List<ForeignKey> foreignKeys) {
		List<ForeignKeyBinding> bindings = new ArrayList<>();
		for (ForeignKey foreignKey : foreignKeys) {
			String childTable = tables.get(foreignKey.entity());
			String parentTable = firstText(tables.get(foreignKey.parent()), foreignKey.parentTable());
			if (childTable == null) {
				throw configurationError("Canonical table is not configured for entity '" + foreignKey.entity() + "'");
			}
			if (parentTable == null) {
				throw configurationError("Canonical table is not configured for entity '" + foreignKey.parent() + "'");
			}
			bindings.add(new ForeignKeyBinding(
					schema,
					childTable,
					foreignKey.column(),
					parentTable,
					foreignKey.referencesColumn()));
		}
		return List.copyOf(bindings);
	}

	private static List<ForeignKey> foreignKeysFromRecords(List<LoadableTargetRecord> records) {
		if (records == null) {
			return List.of();
		}
		Map<String, ForeignKey> foreignKeys = new LinkedHashMap<>();
		for (LoadableTargetRecord record : records) {
			if (record == null || record.targetRecord() == null || record.targetRecord().fields() == null) {
				continue;
			}
			Map<String, Object> payload = record.targetRecord().fields();
			String entityName = text(payload.get("entity"));
			if (entityName == null || !(payload.get("relationships") instanceof List<?> links)) {
				continue;
			}
			for (Object link : links) {
				if (!(link instanceof Map<?, ?> relationship)) {
					continue;
				}
				String parent = text(relationship.get("parentEntity"));
				Object foreignKey = relationship.get("targetForeignKey");
				if (parent == null || !(foreignKey instanceof Map<?, ?> raw)) {
					continue;
				}
				String column = requiredText(raw.get("column"), "Canonical foreign key column is required");
				String referencesColumn = requiredText(
						raw.get("referencesColumn"),
						"Canonical foreign key referencesColumn is required");
				requireIdentifier(column, "Canonical foreign key column is invalid");
				requireIdentifier(referencesColumn, "Canonical foreign key referencesColumn is invalid");
				String parentTargetKey = text(relationship.get("parentTargetKey"));
				if (parentTargetKey != null) {
					requireIdentifier(parentTargetKey, "Canonical parent target key is invalid");
				}
				foreignKeys.putIfAbsent(
						entityName + "\u0000" + parent + "\u0000" + column,
						new ForeignKey(
								entityName,
								parent,
								column,
								referencesColumn,
								parentTargetKey,
								text(relationship.get("parentTargetEntity"))));
			}
		}
		return List.copyOf(foreignKeys.values());
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

	private static String schema(Map<String, Object> configuration) {
		if (configuration == null) {
			throw configurationError("Canonical target configuration is required");
		}
		Object targetValue = configuration.get("target");
		if (!(targetValue instanceof Map<?, ?> target)) {
			throw configurationError("Canonical target configuration is required");
		}
		String mode = text(target.get("mode"));
		if (!MODE.equals(mode) && !"POSTGRESQL".equals(mode)) {
			throw configurationError("Canonical target mode must be UAM or POSTGRESQL");
		}
		String schema = requiredText(target.get("schema"), "Canonical target schema is required");
		requireIdentifier(schema, "Canonical target schema is invalid");
		return schema;
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

	private static String firstText(String preferred, String fallback) {
		return preferred != null ? preferred : fallback;
	}

	private static TargetInfrastructureException configurationError(String message) {
		return new TargetInfrastructureException(MigrationErrorCodes.TARGET_DATABASE_CONFIGURATION_ERROR, message);
	}

	record InsertGroup(String schema, String table, List<String> columns, List<List<Object>> parameters) {
	}

	record PlannedLoad(List<InsertGroup> groups, List<ForeignKeyBinding> foreignKeys) {
	}

	record ForeignKeyBinding(
			String schema,
			String childTable,
			String childColumn,
			String parentTable,
			String referencesColumn) {
	}

	private record PreparedRow(String entityName, String table, Map<String, Object> values) {
	}

	private record MutableRow(
			String entityName,
			String table,
			String targetKey,
			String targetIdentity,
			Map<String, Object> payload,
			Map<String, Object> values) {
	}

	private record ForeignKey(
			String entity,
			String parent,
			String column,
			String referencesColumn,
			String parentTargetKey,
			String parentTable) {
	}
}
