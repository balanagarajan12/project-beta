package com.etp.migration.migration.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record MigrationSourceResponse(
		UUID id,
		UUID migrationDefinitionId,
		String name,
		String sourceType,
		Map<String, Object> configuration,
		boolean enabled,
		Instant createdAt,
		String createdBy,
		Instant updatedAt,
		String updatedBy
) {
}
