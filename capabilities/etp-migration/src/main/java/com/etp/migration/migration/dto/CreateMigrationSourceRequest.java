package com.etp.migration.migration.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public record CreateMigrationSourceRequest(
		@NotBlank(message = "name is required")
		String name,

		@NotBlank(message = "sourceType is required")
		String sourceType,

		Map<String, Object> configuration,

		Boolean enabled
) {
}
