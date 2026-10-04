package com.etp.migration.reconciliation;

/**
 * Observed state of one expected parent/child link.
 * These values describe a result that already happened.
 */
public enum RelationshipCondition {
	RESOLVED,
	PARENT_TARGET_UNRESOLVED,
	CHILD_TARGET_UNRESOLVED,
	REFERENCE_MISSING,
	INCONSISTENT,
	CARDINALITY_VIOLATION
}
