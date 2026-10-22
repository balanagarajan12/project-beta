package com.etp.migration.connector.target.postgresql;

import com.etp.migration.connector.target.MigrationTargetContext;
import com.etp.migration.connector.target.TargetInfrastructureException;
import com.etp.migration.processing.MigrationErrorCodes;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

final class PostgreSqlStagingTarget {

	private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

	private PostgreSqlStagingTarget() {
	}

	static Resolved resolve(MigrationTargetContext context, PostgreSqlTargetProperties properties) {
		return resolve(context.configuration(), properties);
	}

	static Resolved resolve(Map<String, Object> configuration, PostgreSqlTargetProperties properties) {
		Map<String, Object> target = targetMap(configuration);
		// stagingSchema selects the staging table when target.schema names a business schema.
		String schema = firstNonBlank(
				stringValue(target.get("stagingSchema")),
				stringValue(target.get("schema")),
				properties.schema(),
				"migration_staging");
		String table = firstNonBlank(stringValue(target.get("stagingTable")), null);
		if (table == null) {
			throw new TargetInfrastructureException(
					MigrationErrorCodes.TARGET_DATABASE_CONFIGURATION_ERROR,
					"PostgreSQL staging table is not configured");
		}
		if (!IDENTIFIER.matcher(schema).matches() || !IDENTIFIER.matcher(table).matches()) {
			throw new TargetInfrastructureException(
					MigrationErrorCodes.TARGET_DATABASE_CONFIGURATION_ERROR,
					"PostgreSQL staging identifiers are invalid");
		}
		String mergeStrategy = firstNonBlank(stringValue(target.get("mergeStrategy")), "NONE");
		if (!"NONE".equalsIgnoreCase(mergeStrategy)) {
			throw new TargetInfrastructureException(
					MigrationErrorCodes.TARGET_DATABASE_CONFIGURATION_ERROR,
					"Unsupported PostgreSQL merge strategy");
		}
		return new Resolved(schema, table, mergeStrategy.toUpperCase(Locale.ROOT));
	}

	static String quoted(Resolved resolved) {
		return PostgreSqlIdentifiers.quote(resolved.schema()) + "." + PostgreSqlIdentifiers.quote(resolved.table());
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> targetMap(Map<String, Object> configuration) {
		if (configuration == null) {
			return Map.of();
		}
		Object target = configuration.get("target");
		if (target instanceof Map<?, ?> map) {
			return (Map<String, Object>) map;
		}
		return Map.of();
	}

	private static String stringValue(Object value) {
		return value == null ? null : value.toString().trim();
	}

	private static String firstNonBlank(String... values) {
		if (values == null) {
			return null;
		}
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return null;
	}

	record Resolved(String schema, String table, String mergeStrategy) {
	}
}
