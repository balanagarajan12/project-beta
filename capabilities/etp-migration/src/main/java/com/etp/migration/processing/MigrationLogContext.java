package com.etp.migration.processing;

import java.util.UUID;
import org.slf4j.MDC;

/**
 * Puts the migration run and batch identifiers into the SLF4J MDC for the current thread. Closing the scope restores
 * whatever values were present before, so nested scopes and pooled request threads never keep stale identifiers.
 */
public final class MigrationLogContext implements AutoCloseable {

	public static final String RUN_ID = "migrationRunId";
	public static final String BATCH_ID = "migrationBatchId";

	private final String previousRunId;
	private final String previousBatchId;

	private MigrationLogContext(UUID runId, Long batchId) {
		previousRunId = MDC.get(RUN_ID);
		previousBatchId = MDC.get(BATCH_ID);
		put(RUN_ID, runId == null ? null : runId.toString());
		put(BATCH_ID, batchId == null ? null : batchId.toString());
	}

	public static MigrationLogContext forRun(UUID runId) {
		return new MigrationLogContext(runId, null);
	}

	public static MigrationLogContext forBatch(UUID runId, Long batchId) {
		return new MigrationLogContext(runId, batchId);
	}

	@Override
	public void close() {
		put(RUN_ID, previousRunId);
		put(BATCH_ID, previousBatchId);
	}

	private static void put(String key, String value) {
		if (value == null) {
			MDC.remove(key);
		} else {
			MDC.put(key, value);
		}
	}
}
