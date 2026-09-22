package com.etp.migration.batch;

import com.etp.migration.entity.MigrationRun;
import java.util.List;
import java.util.Map;

public interface BatchPlanner {

	List<PlannedBatch> plan(MigrationRun run, BatchPlanRequest request);

	record BatchPlanRequest(long recordCount, int batchSize) {
	}

	record PlannedBatch(int batchNumber, int recordCount, long startOffset, long endOffset, Map<String, Object> sourceLocator) {
	}
}
