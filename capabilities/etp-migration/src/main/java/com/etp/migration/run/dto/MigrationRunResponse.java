package com.etp.migration.run.dto;

import com.etp.migration.entity.MigrationRunStatus;
import com.etp.migration.run.ExecutionControlState;
import java.time.Instant;
import java.util.UUID;

public record MigrationRunResponse(
		UUID id,
		UUID migrationDefinitionId,
		Long runNumber,
		String idempotencyKey,
		MigrationRunStatus status,
		Instant startedAt,
		Instant completedAt,
		Long totalRecords,
		Long processedRecords,
		Long successRecords,
		Long duplicateRecords,
		Long validationFailures,
		Long enrichmentFailures,
		Long loadFailures,
		Long rejectedRecords,
		Integer totalBatches,
		Integer completedBatches,
		Integer failedBatches,
		String requestedBy,
		Instant createdAt,
		Instant updatedAt,
		ExecutionControlState controlState,
		Instant controlUpdatedAt,
		String lastControlAction
) {
	public MigrationRunResponse(
			UUID id,
			UUID migrationDefinitionId,
			Long runNumber,
			String idempotencyKey,
			MigrationRunStatus status,
			Instant startedAt,
			Instant completedAt,
			Long totalRecords,
			Long processedRecords,
			Long successRecords,
			Long duplicateRecords,
			Long validationFailures,
			Long enrichmentFailures,
			Long loadFailures,
			Long rejectedRecords,
			Integer totalBatches,
			Integer completedBatches,
			Integer failedBatches,
			String requestedBy,
			Instant createdAt,
			Instant updatedAt) {
		this(
				id,
				migrationDefinitionId,
				runNumber,
				idempotencyKey,
				status,
				startedAt,
				completedAt,
				totalRecords,
				processedRecords,
				successRecords,
				duplicateRecords,
				validationFailures,
				enrichmentFailures,
				loadFailures,
				rejectedRecords,
				totalBatches,
				completedBatches,
				failedBatches,
				requestedBy,
				createdAt,
				updatedAt,
				ExecutionControlState.RUNNING,
				null,
				null);
	}
}
