package com.etp.migration.run.dto;

import com.etp.migration.run.MigrationPhase;
import com.etp.migration.run.PhaseJobStatus;
import java.util.UUID;

/**
 * Acceptance body for reconcile, promotion preparation, and promote.
 * {@code status} is the job status. It is not the reconciliation outcome or the promotion result.
 */
public record PhaseAcceptanceResponse(
		UUID jobId,
		UUID runId,
		UUID promotionId,
		MigrationPhase phase,
		PhaseJobStatus status,
		String outcome,
		String message,
		String errorCode,
		String errorMessage,
		String statusUrl
) {
}
