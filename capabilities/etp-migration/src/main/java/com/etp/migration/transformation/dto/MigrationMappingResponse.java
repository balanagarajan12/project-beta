package com.etp.migration.transformation.dto;

import com.etp.migration.entity.MigrationConfigStatus;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record MigrationMappingResponse(
		UUID id,
		String name,
		String version,
		String sourceType,
		String targetType,
		String description,
		Map<String, Object> configuration,
		MigrationConfigStatus status,
		Instant createdAt,
		String createdBy,
		Instant updatedAt,
		String updatedBy
) {
}
