package com.etp.migration.promotion.dto;

import com.etp.migration.entity.PromotionStatus;
import java.util.List;
import java.util.UUID;

public record PreparePromotionResponse(
		UUID promotionId,
		UUID runId,
		PromotionStatus status,
		int totalBatches,
		int eligibleBatches,
		int skippedBatches,
		long totalRecords,
		long eligibleRecords,
		long skippedRecords,
		List<PromotionBatchEligibilityResponse> batches
) {
}
