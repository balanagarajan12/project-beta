package com.etp.migration.reconciliation;

/**
 * One observed parent/child link. Identity is the pair of entity name and source identity.
 * A parent source identity is enough to accept the link. Target identities are optional here.
 * A parent target identity that repeats the parent source identity is inconsistent.
 */
public record RelationshipFact(
		String parentEntity,
		String parentSourceIdentity,
		String parentTargetIdentity,
		String childTargetIdentity,
		RelationshipCondition condition,
		String cardinality) {

	public static RelationshipFact resolved(
			String parentEntity,
			String parentSourceIdentity,
			String parentTargetIdentity,
			String childTargetIdentity) {
		return new RelationshipFact(
				parentEntity,
				parentSourceIdentity,
				parentTargetIdentity,
				childTargetIdentity,
				RelationshipCondition.RESOLVED,
				null);
	}

	public static RelationshipFact observed(
			String parentEntity,
			String parentSourceIdentity,
			String parentTargetIdentity,
			String childTargetIdentity) {
		return new RelationshipFact(
				parentEntity,
				parentSourceIdentity,
				parentTargetIdentity,
				childTargetIdentity,
				null,
				null);
	}

	public static RelationshipFact issue(
			String parentEntity,
			String parentSourceIdentity,
			String childTargetIdentity,
			RelationshipCondition condition,
			String cardinality) {
		return new RelationshipFact(
				parentEntity,
				parentSourceIdentity,
				null,
				childTargetIdentity,
				condition,
				cardinality);
	}
}
