package com.etp.migration.processing;

import com.etp.migration.entity.MigrationStage;
import java.util.Map;

public record ProcessingError(
		MigrationStage stage,
		String errorCode,
		String errorType,
		String fieldName,
		String message,
		Map<String, Object> details,
		boolean retryable
) {
}
