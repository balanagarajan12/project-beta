package com.etp.migration.reconciliation;

import java.util.UUID;

public class ReconciliationNotFoundException extends RuntimeException {

	public ReconciliationNotFoundException(UUID runId) {
		super("Reconciliation not found for migration run " + runId);
	}
}
