package com.etp.migration.planning;

import java.util.List;

/**
 * One bounded execution batch. It may contain work from more than one logical scope.
 * {@code batchNumber} starts at 1 and is local to the plan. This type is not a {@code migration_batch} row.
 */
public record ExecutionBatchPlan(int batchNumber, List<ExecutionWorkItem> workItems) {
	public ExecutionBatchPlan {
		workItems = workItems == null ? List.of() : List.copyOf(workItems);
	}
}
