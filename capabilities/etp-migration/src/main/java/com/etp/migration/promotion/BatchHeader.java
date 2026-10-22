package com.etp.migration.promotion;

import com.etp.migration.entity.MigrationBatchStatus;

/**
 * Batch identity and execution status. Preparation uses this instead of the batch entity so
 * {@code source_locator} JSON is not deserialized.
 */
public record BatchHeader(Long id, Integer batchNumber, MigrationBatchStatus status) {
}
