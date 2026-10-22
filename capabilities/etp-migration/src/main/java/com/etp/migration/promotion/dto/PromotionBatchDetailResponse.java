package com.etp.migration.promotion.dto;

import com.etp.migration.entity.PromotionBatchStatus;
import java.time.Instant;

/**
 * One promotion batch. {@code durationMillis} is the latest attempt:
 * {@code completedAt - startedAt} when the attempt finished, otherwise {@code now - startedAt}.
 * It is not stored and is not the sum of earlier attempts.
 */
public record PromotionBatchDetailResponse(
		Long batchId,
		Integer batchNumber,
		PromotionBatchStatus promotionStatus,
		long eligibleRecordCount,
		long promotedRecordCount,
		long failedRecordCount,
		long skippedRecordCount,
		Instant startedAt,
		Instant completedAt,
		Long durationMillis,
		String errorCode,
		String errorMessage) {
}
