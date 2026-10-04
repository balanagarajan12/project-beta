package com.etp.migration.scope;

import java.util.List;

/**
 * Relationship scope rooted at one source identity of the configured root entity.
 * This is the business/entity relationship unit. An execution batch is a separate throughput unit.
 * Members are identities, not source payloads, target ids, or a database transaction.
 */
public record LogicalMigrationScope(
		String rootEntity,
		String rootIdentity,
		List<ScopedEntity> entities,
		List<ScopedRelationship> relationships
) {
	public LogicalMigrationScope {
		entities = entities == null ? List.of() : List.copyOf(entities);
		relationships = relationships == null ? List.of() : List.copyOf(relationships);
	}

	public boolean contains(String entityName, String sourceIdentity) {
		return member(entityName, sourceIdentity) != null;
	}

	public ScopedEntity member(String entityName, String sourceIdentity) {
		for (ScopedEntity entity : entities) {
			if (entity.entityName().equals(entityName) && entity.sourceIdentity().equals(sourceIdentity)) {
				return entity;
			}
		}
		return null;
	}
}
