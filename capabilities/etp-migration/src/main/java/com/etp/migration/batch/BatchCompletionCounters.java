package com.etp.migration.batch;

public record BatchCompletionCounters(
		int processedCount,
		int successCount,
		int duplicateCount,
		int validationFailureCount,
		int enrichmentFailureCount,
		int loadFailureCount,
		int rejectedCount
) {
}
