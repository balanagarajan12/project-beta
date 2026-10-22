package com.etp.migration.promotion;

import com.etp.migration.entity.PromotionBatchStatus;

/** Eligibility stored for one batch, without the execution batch payload. */
public record PromotionBatchHeader(
		Long migrationBatchId,
		PromotionBatchStatus status,
		Long eligibleRecordCount,
		Long skippedRecordCount) {
}
