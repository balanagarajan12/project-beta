package com.etp.migration.promotion.dto;

import com.etp.migration.entity.PromotionBatchStatus;

public record PromotionBatchExecutionResponse(
		Long batchId,
		PromotionBatchStatus promotionStatus,
		long eligibleRecordCount,
		long promotedRecordCount,
		long failedRecordCount,
		String errorCode,
		String errorMessage
) {
}
