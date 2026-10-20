package com.etp.migration.run.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record MigrationErrorResponse(
		Long id,
		UUID migrationRunId,
		Long migrationBatchId,
		String stage,
		String errorCode,
		String errorType,
		String sourceRecordKey,
		String fieldName,
		String errorMessage,
		Map<String, Object> errorDetails,
		boolean retryable,
		Instant createdAt
) {
}
