package com.etp.migration.migration.dto;

import com.etp.migration.transformation.dto.MigrationMappingResponse;
import com.etp.migration.transformation.dto.MigrationMappingRuleResponse;
import java.util.List;

public record CompositeMigrationConfigurationResponse(
		MigrationDefinitionResponse definition,
		MigrationMappingResponse mapping,
		List<MigrationMappingRuleResponse> rules,
		List<MigrationSourceResponse> sources
) {
}
