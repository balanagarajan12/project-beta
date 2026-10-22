package com.etp.migration.promotion;

import java.util.Map;
import java.util.UUID;

public interface StagingRecordCounter {

	long countStagedRecords(UUID migrationRunId, Long migrationBatchId, Map<String, Object> configuration);

	/**
	 * Staged row counts for every batch of one run. One grouped query, not one query per batch.
	 */
	Map<Long, Long> countStagedRecordsByBatch(UUID migrationRunId, Map<String, Object> configuration);
}
