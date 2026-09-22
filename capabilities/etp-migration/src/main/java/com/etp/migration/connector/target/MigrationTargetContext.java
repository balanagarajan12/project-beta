package com.etp.migration.connector.target;

import com.etp.migration.entity.LoadMode;
import java.util.Map;
import java.util.UUID;

public record MigrationTargetContext(
		UUID migrationRunId,
		Long migrationBatchId,
		Integer batchNumber,
		String targetType,
		String targetEntity,
		LoadMode loadMode,
		Map<String, Object> configuration
) {
}
