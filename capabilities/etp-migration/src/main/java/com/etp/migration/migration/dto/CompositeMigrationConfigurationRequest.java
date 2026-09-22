package com.etp.migration.migration.dto;

import com.etp.migration.transformation.dto.CreateMappingRuleRequest;
import com.etp.migration.transformation.dto.CreateMigrationMappingRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record CompositeMigrationConfigurationRequest(
		@NotNull(message = "definition is required")
		@Valid
		CompositeDefinitionRequest definition,

		@NotNull(message = "mapping is required")
		@Valid
		CreateMigrationMappingRequest mapping,

		@NotEmpty(message = "rules are required")
		List<@Valid CreateMappingRuleRequest> rules,

		@NotNull(message = "source is required")
		@Valid
		CreateMigrationSourceRequest source
) {
}
