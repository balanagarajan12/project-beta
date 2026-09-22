package com.etp.migration.batch.dto;

import com.etp.migration.entity.MigrationBatchStatus;
import java.time.Instant;
import java.util.UUID;

public record MigrationBatchResponse(
		Long id,
		UUID migrationRunId,
		Integer batchNumber,
		MigrationBatchStatus status,
		Integer recordCount,
		Integer processedCount,
		Integer successCount,
		Integer duplicateCount,
		Integer validationFailureCount,
		Integer enrichmentFailureCount,
		Integer loadFailureCount,
		Integer rejectedCount,
		Integer attemptCount,
		String workerId,
		Instant claimedAt,
		Instant leaseUntil,
		Instant startedAt,
		Instant completedAt,
		String errorMessage
) {
}
