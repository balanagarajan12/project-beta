package com.etp.migration.relationship;

import java.util.List;

/**
 * Source-side relationship matches for one resolution pass.
 * {@code indexedParentCount} is the number of parent references kept for lookup, not a count of child records.
 */
public record RelationshipResolution(
		List<ResolvedRelationship> relationships,
		List<RelationshipResolutionFailure> failures,
		int indexedParentCount
) {
	public RelationshipResolution {
		relationships = relationships == null ? List.of() : List.copyOf(relationships);
		failures = failures == null ? List.of() : List.copyOf(failures);
	}
}
