package com.etp.migration.relationship;

import com.etp.migration.mapping.RelationshipCardinality;
import java.util.List;

/**
 * One source-side association. Identities are source identities, not target keys.
 */
public record ResolvedRelationship(
		String parentEntity,
		String parentIdentity,
		long parentSourceRecordNumber,
		String childEntity,
		String childIdentity,
		long childSourceRecordNumber,
		RelationshipCardinality cardinality,
		List<RelationshipKeyMatch> relationshipKeys
) {
	public ResolvedRelationship {
		relationshipKeys = relationshipKeys == null ? List.of() : List.copyOf(relationshipKeys);
	}
}
