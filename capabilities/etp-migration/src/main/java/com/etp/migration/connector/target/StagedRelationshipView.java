package com.etp.migration.connector.target;

/**
 * Parent link read from a staged target payload. Values are copied out of the payload.
 * Reconciliation does not parse target JSON itself.
 */
public record StagedRelationshipView(
		String parentEntity,
		String parentSourceIdentity,
		String parentTargetIdentity) {
}
