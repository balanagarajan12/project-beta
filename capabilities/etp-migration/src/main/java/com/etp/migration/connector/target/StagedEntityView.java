package com.etp.migration.connector.target;

import java.util.List;

/**
 * One staged entity row projected for reconciliation.
 * The projection is aggregate input. It is not a second copy of the migration.
 */
public record StagedEntityView(
		String entityName,
		String sourceIdentity,
		String targetIdentity,
		List<StagedRelationshipView> relationships) {

	public StagedEntityView {
		relationships = relationships == null ? List.of() : List.copyOf(relationships);
	}
}
