package com.etp.migration.run.dto;

import com.etp.migration.entity.MigrationRunStatus;
import com.etp.migration.run.MigrationRunTransitions;
import java.util.UUID;

/**
 * Acceptance body for {@code POST /execute}. {@code status} is the run status at acceptance time.
 * It is not a promise that preparation or batch processing has finished.
 */
public record MigrationExecutionResponse(
		UUID id,
		UUID runId,
		MigrationRunStatus status,
		String message,
		String statusUrl
) {
	public static MigrationExecutionResponse from(MigrationRunResponse run) {
		boolean finished = MigrationRunTransitions.isTerminal(run.status());
		return new MigrationExecutionResponse(
				run.id(),
				run.id(),
				run.status(),
				finished ? "Migration run already exists." : "Migration execution accepted.",
				"/api/v1/migration-runs/" + run.id());
	}
}
