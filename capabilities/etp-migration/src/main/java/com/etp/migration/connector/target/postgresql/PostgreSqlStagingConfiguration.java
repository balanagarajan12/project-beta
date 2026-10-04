package com.etp.migration.connector.target.postgresql;

import com.etp.migration.entity.LoadMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Configuration-contract checks for a PostgreSQL bulk target.
 * Does not connect to a database or verify that the table exists.
 */
public final class PostgreSqlStagingConfiguration {

	private PostgreSqlStagingConfiguration() {
	}

	public static List<StagingFieldViolation> violations(
			String targetType,
			LoadMode loadMode,
			Map<String, Object> configuration,
			String fieldPrefix) {
		if (targetType == null || !targetType.equalsIgnoreCase("POSTGRESQL") || loadMode != LoadMode.BULK) {
			return List.of();
		}
		Map<?, ?> target = target(configuration);
		String table = text(target == null ? null : target.get("stagingTable"));
		String schema = text(target == null ? null : target.get("stagingSchema"));
		List<StagingFieldViolation> violations = new ArrayList<>();
		String tableField = fieldPrefix + ".target.stagingTable";
		if (table == null || table.isBlank()) {
			violations.add(new StagingFieldViolation(
					tableField,
					null,
					"stagingTable is required for POSTGRESQL target"));
		}
		else if (!PostgreSqlIdentifiers.isValid(table)) {
			violations.add(new StagingFieldViolation(
					tableField,
					table,
					"stagingTable is not a valid PostgreSQL identifier"));
		}
		if (schema != null && !schema.isBlank() && !PostgreSqlIdentifiers.isValid(schema)) {
			violations.add(new StagingFieldViolation(
					fieldPrefix + ".target.stagingSchema",
					schema,
					"stagingSchema is not a valid PostgreSQL identifier"));
		}
		return List.copyOf(violations);
	}

	private static Map<?, ?> target(Map<String, Object> configuration) {
		if (configuration == null || !(configuration.get("target") instanceof Map<?, ?> target)) {
			return null;
		}
		return target;
	}

	private static String text(Object value) {
		if (value == null) {
			return null;
		}
		return value instanceof String text ? text : String.valueOf(value);
	}

	public record StagingFieldViolation(String field, String rejectedValue, String message) {
	}
}
