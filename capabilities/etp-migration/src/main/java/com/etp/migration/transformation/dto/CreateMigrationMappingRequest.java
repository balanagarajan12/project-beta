package com.etp.migration.transformation.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public record CreateMigrationMappingRequest(
		@NotBlank(message = "name is required")
		String name,

		@NotBlank(message = "version is required")
		String version,

		@NotBlank(message = "sourceType is required")
		String sourceType,

		@NotBlank(message = "targetType is required")
		String targetType,

		String description,

		Map<String, Object> configuration
) {
}
