package com.etp.migration.batch;

import com.etp.migration.entity.MigrationBatch;

/**
 * Identifies one claim of a batch. A later claim of the same batch has a higher attempt number.
 */
public record BatchClaim(Long batchId, String workerId, int attempt) {

	public static BatchClaim of(MigrationBatch batch) {
		return new BatchClaim(
				batch.getId(),
				batch.getWorkerId(),
				batch.getAttemptCount() == null ? 0 : batch.getAttemptCount());
	}
}
