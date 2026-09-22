package com.etp.migration.batch;

import java.util.UUID;

public class MigrationBatchNotFoundException extends RuntimeException {

	public MigrationBatchNotFoundException(Long batchId) {
		super("Migration batch not found: " + batchId);
	}

	public MigrationBatchNotFoundException(UUID runId) {
		super("No claimable batch is available for migration run " + runId);
	}
}
