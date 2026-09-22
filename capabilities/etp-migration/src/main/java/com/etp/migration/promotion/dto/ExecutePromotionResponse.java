package com.etp.migration.promotion.dto;

import com.etp.migration.entity.PromotionStatus;
import java.util.List;
import java.util.UUID;

public record ExecutePromotionResponse(
		UUID promotionId,
		UUID runId,
		PromotionStatus status,
		int totalBatches,
		int eligibleBatches,
		int promotedBatches,
		int failedBatches,
		int skippedBatches,
		long totalRecords,
		long promotedRecords,
		long failedRecords,
		long skippedRecords,
		List<PromotionBatchExecutionResponse> batches
) {
}
