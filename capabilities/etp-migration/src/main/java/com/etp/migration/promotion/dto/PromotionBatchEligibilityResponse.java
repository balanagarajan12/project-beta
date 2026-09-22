package com.etp.migration.promotion.dto;

import com.etp.migration.entity.MigrationBatchStatus;
import com.etp.migration.entity.PromotionBatchStatus;

public record PromotionBatchEligibilityResponse(
		Long batchId,
		MigrationBatchStatus executionStatus,
		PromotionBatchStatus promotionStatus,
		long eligibleRecordCount,
		long skippedRecordCount
) {
}
