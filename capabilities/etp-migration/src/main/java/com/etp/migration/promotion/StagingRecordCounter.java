package com.etp.migration.promotion;

import java.util.Map;
import java.util.UUID;

public interface StagingRecordCounter {

	long countStagedRecords(UUID migrationRunId, Long migrationBatchId, Map<String, Object> configuration);
}
