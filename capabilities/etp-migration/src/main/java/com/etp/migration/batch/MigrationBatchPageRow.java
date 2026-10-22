package com.etp.migration.batch;

import com.etp.migration.entity.MigrationBatchStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Batch columns for the monitoring page. {@code source_locator} is not selected.
 */
public record MigrationBatchPageRow(
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
		String errorMessage) {
}
