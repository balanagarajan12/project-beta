package com.etp.migration.migration;

import com.etp.migration.mapping.EntityDefinition;
import com.etp.migration.mapping.EntityRelationshipConfiguration;
import com.etp.migration.mapping.EntityRelationshipMapping;
import java.util.Map;
import java.util.Optional;

/**
 * Decides whether {@code definition.targetEntity} is required.
 * Source type is not used. The mapping model selects the rule.
 */
public final class MigrationModelRules {

	private MigrationModelRules() {
	}

	public static void validate(String targetEntity, Map<String, Object> mappingConfiguration) {
		Optional<EntityRelationshipMapping> model = EntityRelationshipConfiguration.parseIfPresent(
				mappingConfiguration == null ? Map.of() : mappingConfiguration);
		String supplied = blankToNull(targetEntity);
		if (model.isEmpty()) {
			if (supplied == null) {
				throw new IllegalArgumentException(
						"definition.targetEntity is required for a single-entity migration");
			}
			return;
		}
		if (supplied == null) {
			return;
		}
		EntityRelationshipMapping mapping = model.get();
		EntityDefinition root = root(mapping);
		if (root != null && (supplied.equals(root.name()) || supplied.equals(root.targetEntity()))) {
			return;
		}
		for (EntityDefinition entity : mapping.entities()) {
			if (root != null && entity.name().equals(root.name())) {
				continue;
			}
			boolean namesAnotherEntity = supplied.equals(entity.name());
			boolean namesAnotherTarget = supplied.equals(entity.targetEntity())
					&& (root == null || !supplied.equals(root.targetEntity()));
			if (namesAnotherEntity || namesAnotherTarget) {
				throw new IllegalArgumentException(
						"definition.targetEntity '" + supplied + "' contradicts root entity '"
								+ mapping.rootEntity() + "'");
			}
		}
	}

	private static EntityDefinition root(EntityRelationshipMapping mapping) {
		for (EntityDefinition entity : mapping.entities()) {
			if (entity.name().equals(mapping.rootEntity())) {
				return entity;
			}
		}
		return null;
	}

	private static String blankToNull(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value.trim();
	}
}
