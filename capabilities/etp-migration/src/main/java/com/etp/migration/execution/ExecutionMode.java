package com.etp.migration.execution;

import com.etp.migration.mapping.EntityRelationshipConfiguration;
import java.util.Map;

/**
 * Flat mappings stay on the existing record pipeline.
 * {@code modelType ENTITY_RELATIONSHIP} selects the entity pipeline.
 */
public final class ExecutionMode {

	private ExecutionMode() {
	}

	public static boolean entityRelationship(Map<String, Object> mappingConfiguration) {
		return EntityRelationshipConfiguration.parseIfPresent(
				mappingConfiguration == null ? Map.of() : mappingConfiguration).isPresent();
	}
}
