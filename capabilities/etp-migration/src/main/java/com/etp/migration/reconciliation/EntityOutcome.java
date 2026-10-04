package com.etp.migration.reconciliation;

/**
 * Observed outcome of one entity work item.
 * Reconciliation records the outcome. It does not change it.
 */
public enum EntityOutcome {
	SUCCESS,
	FAILURE,
	IDENTITY_UNRESOLVED,
	LOAD_FAILURE
}
