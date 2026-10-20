package com.etp.migration.migration.dto;

import com.etp.migration.transformation.dto.CreateMappingRuleRequest;
import com.etp.migration.transformation.dto.CreateMigrationMappingRequest;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record CompositeMigrationConfigurationRequest(
		@NotNull(message = "definition is required")
		@Valid
		CompositeDefinitionRequest definition,

		@JsonProperty("mapping")
		@JsonAlias("entityMappings")
		@NotNull(message = "entityMappings is required")
		@Valid
		CreateMigrationMappingRequest mapping,

		@JsonProperty("rules")
		@JsonAlias("fieldMappings")
		@NotEmpty(message = "fieldMappings are required")
		List<@Valid CreateMappingRuleRequest> rules,

		@NotNull(message = "source is required")
		@Valid
		CreateMigrationSourceRequest source
) {
}
