package com.etp.migration.migration;

import java.util.Map;
import java.util.Set;

/**
 * Canonical {@code definition.configuration} contains {@code target} and {@code promotion}.
 * Tables, load order, foreign keys, timestamp columns, and column types are derived elsewhere.
 */
public final class DefinitionConfigurationContract {

	static final Set<String> ALLOWED = Set.of("target", "promotion");
	static final Set<String> PROHIBITED = Set.of(
			"tables",
			"loadOrder",
			"foreignKeys",
			"loadTimestampColumns",
			"columnTypes");

	private DefinitionConfigurationContract() {
	}

	public static void require(Map<String, Object> configuration) {
		if (configuration == null || configuration.isEmpty()) {
			return;
		}
		for (String key : configuration.keySet()) {
			if (PROHIBITED.contains(key)) {
				throw new IllegalArgumentException(
						"definition.configuration." + key
								+ " is not part of the migration payload. "
								+ "Entity mappings supply target tables and foreign keys. "
								+ "Load order is derived from those relationships. "
								+ "Column types and timestamps come from the target database.");
			}
			if (!ALLOWED.contains(key)) {
				throw new IllegalArgumentException(
						"definition.configuration accepts only target and promotion");
			}
		}
	}
}
