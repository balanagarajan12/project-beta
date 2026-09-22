package com.etp.migration.transformation.dto;

import com.etp.migration.entity.TransformationType;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record MigrationMappingRuleResponse(
		Long id,
		UUID mappingId,
		Integer sequenceNo,
		String sourceField,
		String targetField,
		TransformationType transformationType,
		String transformationExpression,
		String defaultValue,
		Map<String, Object> configuration,
		boolean mandatory,
		boolean enabled,
		Instant createdAt,
		Instant updatedAt
) {
}
