package com.etp.migration.connector.target;

import java.util.List;

public record TargetLoadResult(
		int submittedCount,
		int successCount,
		int failedCount,
		List<TargetReference> targetReferences,
		List<TargetRecordError> errors
) {
}
