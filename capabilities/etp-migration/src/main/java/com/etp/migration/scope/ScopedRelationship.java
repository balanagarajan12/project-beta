package com.etp.migration.scope;

import com.etp.migration.mapping.RelationshipCardinality;

/**
 * One source-side relationship inside a logical scope.
 * The edge is the configured parent entity and child entity. Identities are source identities.
 */
public record ScopedRelationship(
		String parentEntity,
		String parentIdentity,
		String childEntity,
		String childIdentity,
		RelationshipCardinality cardinality
) {
}
