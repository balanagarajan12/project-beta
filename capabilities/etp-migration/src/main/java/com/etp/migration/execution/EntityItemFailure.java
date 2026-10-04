package com.etp.migration.execution;

import com.etp.migration.entity.MigrationStage;
import java.util.Map;

/**
 * One entity work item that did not load. Source identity is kept when the entity had one.
 */
public record EntityItemFailure(
		long sourceRecordNumber,
		String entityName,
		String sourceIdentity,
		MigrationStage stage,
		String errorCode,
		String errorType,
		String fieldName,
		String message,
		Map<String, Object> details,
		boolean retryable
) {
	public EntityItemFailure {
		details = details == null ? Map.of() : Map.copyOf(details);
	}
}
