package com.etp.migration.reconciliation.dto;

import com.etp.migration.entity.ReconciliationStatus;
import java.util.UUID;

public record MigrationReconciliationResponse(
		UUID runId,
		ReconciliationStatus status,
		ReconciliationBundleResponse reconciliation,
		UUID jobId,
		String jobStatus
) {
	public MigrationReconciliationResponse(
			UUID runId,
			ReconciliationStatus status,
			ReconciliationBundleResponse reconciliation) {
		this(runId, status, reconciliation, null, null);
	}

	public MigrationReconciliationResponse withJob(UUID jobId, String jobStatus) {
		return new MigrationReconciliationResponse(runId, status, reconciliation, jobId, jobStatus);
	}
}
