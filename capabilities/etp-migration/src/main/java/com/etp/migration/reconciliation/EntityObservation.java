package com.etp.migration.reconciliation;

import java.util.List;

/**
 * One source entity instance and the execution result already produced for it.
 * The accumulator does not retain these values after they have been counted.
 */
public record EntityObservation(
		String entityName,
		String sourceIdentity,
		boolean planned,
		boolean processed,
		EntityOutcome outcome,
		String targetIdentity,
		List<RelationshipFact> relationships) {

	public EntityObservation {
		relationships = relationships == null ? List.of() : List.copyOf(relationships);
	}

	public static EntityObservation success(
			String entityName,
			String sourceIdentity,
			String targetIdentity,
			List<RelationshipFact> relationships) {
		return new EntityObservation(
				entityName,
				sourceIdentity,
				true,
				true,
				EntityOutcome.SUCCESS,
				targetIdentity,
				relationships);
	}

	public static EntityObservation unprocessed(String entityName, String sourceIdentity) {
		return new EntityObservation(entityName, sourceIdentity, false, false, null, null, List.of());
	}

	public static EntityObservation outcome(
			String entityName,
			String sourceIdentity,
			EntityOutcome outcome,
			String targetIdentity,
			List<RelationshipFact> relationships) {
		return new EntityObservation(entityName, sourceIdentity, true, true, outcome, targetIdentity, relationships);
	}
}
