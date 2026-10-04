package com.etp.migration.mapping;

import java.util.List;

/**
 * Typed view of {@code migration_mapping.configuration} when {@code modelType} is ENTITY_RELATIONSHIP.
 */
public record EntityRelationshipMapping(
		String rootEntity,
		List<EntityDefinition> entities,
		List<RelationshipDefinition> relationships
) {
	public EntityRelationshipMapping {
		entities = entities == null ? List.of() : List.copyOf(entities);
		relationships = relationships == null ? List.of() : List.copyOf(relationships);
	}

	public EntityRelationshipGraph graph() {
		return EntityRelationshipGraph.from(this);
	}
}
