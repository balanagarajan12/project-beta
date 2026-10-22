package com.etp.migration.promotion;

import com.etp.migration.entity.PromotionBatchStatus;
import java.time.Instant;

/**
 * Promotion-batch columns needed by the monitoring page.
 * The migration batch contributes only its number, so {@code source_locator} stays unread.
 */
public record PromotionBatchRow(
		Long migrationBatchId,
		Integer batchNumber,
		PromotionBatchStatus status,
		Long eligibleRecordCount,
		Long promotedRecordCount,
		Long failedRecordCount,
		Long skippedRecordCount,
		Instant startedAt,
		Instant completedAt,
		String errorCode,
		String errorMessage) {
}
