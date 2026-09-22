package com.etp.migration.transformation.dto;

import com.etp.migration.entity.TransformationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.Map;

public record CreateMappingRuleRequest(
		@NotNull(message = "sequenceNo is required")
		@Positive(message = "sequenceNo must be greater than 0")
		Integer sequenceNo,

		String sourceField,

		@NotBlank(message = "targetField is required")
		String targetField,

		@NotNull(message = "transformationType is required")
		TransformationType transformationType,

		String transformationExpression,

		String defaultValue,

		Map<String, Object> configuration,

		Boolean mandatory,

		Boolean enabled
) {
}
