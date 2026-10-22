package com.etp.migration.migration.dto;

import com.etp.migration.entity.LoadMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.Map;
import java.util.UUID;

public record CompositeDefinitionRequest(
		@NotBlank(message = "name is required")
		String name,

		@NotBlank(message = "version is required")
		String version,

		String description,

		@NotBlank(message = "sourceType is required")
		String sourceType,

		@NotBlank(message = "targetType is required")
		String targetType,

		String targetEntity,

		@Positive(message = "batchSize must be greater than 0")
		Integer batchSize,

		LoadMode loadMode,

		@Schema(description = "Only target and promotion. Target tables, relationships, load order, column types, and timestamps are not stored here.")
		Map<String, Object> configuration
) {
	public CreateMigrationDefinitionRequest toCreateRequest(UUID mappingId) {
		return new CreateMigrationDefinitionRequest(
				name,
				version,
				description,
				sourceType,
				targetType,
				targetEntity,
				mappingId,
				batchSize,
				loadMode,
				configuration);
	}
}
