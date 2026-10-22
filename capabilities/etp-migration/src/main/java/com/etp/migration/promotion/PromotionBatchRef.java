package com.etp.migration.promotion;

/** Promotion-batch identity used to resume a load without reading the execution-batch payload. */
public record PromotionBatchRef(Long promotionBatchId, Long migrationBatchId, Long eligibleRecordCount) {
}
