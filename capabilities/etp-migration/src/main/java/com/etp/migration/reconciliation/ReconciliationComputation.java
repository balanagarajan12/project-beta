package com.etp.migration.reconciliation;

import com.etp.migration.entity.ReconciliationStatus;
import com.etp.migration.entity.ReconciliationType;
import java.util.Map;

public record ReconciliationComputation(
		ReconciliationType type,
		ReconciliationStatus status,
		long sourceCount,
		long processedCount,
		long successCount,
		long rejectedCount,
		long duplicateCount,
		long failureCount,
		long differenceCount,
		Map<String, Object> details
) {
	public ReconciliationComputation withStatus(ReconciliationStatus status) {
		return new ReconciliationComputation(
				type,
				status,
				sourceCount,
				processedCount,
				successCount,
				rejectedCount,
				duplicateCount,
				failureCount,
				differenceCount,
				details);
	}
}
