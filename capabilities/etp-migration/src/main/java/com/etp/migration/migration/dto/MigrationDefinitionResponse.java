package com.etp.migration.migration.dto;

import com.etp.migration.entity.LoadMode;
import com.etp.migration.entity.MigrationConfigStatus;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record MigrationDefinitionResponse(
		UUID id,
		String name,
		String version,
		String description,
		String sourceType,
		String targetType,
		String targetEntity,
		UUID mappingId,
		Integer batchSize,
		LoadMode loadMode,
		MigrationConfigStatus status,
		Map<String, Object> configuration,
		Instant createdAt,
		String createdBy,
		Instant updatedAt,
		String updatedBy
) {
}
