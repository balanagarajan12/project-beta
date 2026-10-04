package com.etp.migration.execution;

import com.etp.migration.entity.LoadMode;
import com.etp.migration.entity.MigrationMappingRule;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record StoredEntityBatch(
		Long batchId,
		int batchNumber,
		int recordCount,
		UUID runId,
		Map<String, Object> sourceConfiguration,
		Map<String, Object> mappingConfiguration,
		List<MigrationMappingRule> rules,
		Map<String, Object> sourceLocator,
		String targetType,
		String targetEntity,
		LoadMode loadMode,
		Map<String, Object> targetConfiguration
) {
}
