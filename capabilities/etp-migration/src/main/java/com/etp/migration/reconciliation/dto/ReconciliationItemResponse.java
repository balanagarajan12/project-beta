package com.etp.migration.reconciliation.dto;

import com.etp.migration.entity.ReconciliationStatus;
import com.etp.migration.entity.ReconciliationType;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ReconciliationItemResponse(
		UUID id,
		ReconciliationType type,
		ReconciliationStatus status,
		Long sourceCount,
		Long processedCount,
		Long successCount,
		Long rejectedCount,
		Long duplicateCount,
		Long failureCount,
		Long differenceCount,
		Map<String, Object> details,
		Instant startedAt,
		Instant completedAt
) {
}
