package com.etp.migration.processing;

import java.util.UUID;

/**
 * Idempotency identity for one flat source row.
 * The key remains {@code runId:batchId:sourceRecordNumber}.
 */
public final class FlatIdempotencyKey {

	private FlatIdempotencyKey() {
	}

	public static String of(UUID migrationRunId, long batchId, long sourceRecordNumber) {
		return migrationRunId + ":" + batchId + ":" + sourceRecordNumber;
	}
}
