package com.etp.migration.relationship;

public record RelationshipResolutionFailure(
		Kind kind,
		String parentEntity,
		String childEntity,
		String childIdentity,
		Long childSourceRecordNumber,
		String message
) {
	public enum Kind {
		UNRESOLVED_PARENT,
		CARDINALITY_VIOLATION,
		DUPLICATE_PARENT_IDENTITY
	}
}
