package com.etp.migration.migration;

import com.etp.migration.migration.dto.CompositeMigrationConfigurationRequest;
import com.etp.migration.migration.dto.CompositeMigrationConfigurationResponse;
import com.etp.migration.migration.dto.MigrationDefinitionResponse;
import com.etp.migration.migration.dto.MigrationSourceResponse;
import com.etp.migration.transformation.MigrationMappingService;
import com.etp.migration.transformation.dto.CreateMappingRuleRequest;
import com.etp.migration.transformation.dto.MigrationMappingResponse;
import com.etp.migration.transformation.dto.MigrationMappingRuleResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CompositeMigrationConfigurationService {

	private final MigrationMappingService migrationMappingService;
	private final MigrationDefinitionService migrationDefinitionService;
	private final MigrationSourceService migrationSourceService;

	public CompositeMigrationConfigurationService(
			MigrationMappingService migrationMappingService,
			MigrationDefinitionService migrationDefinitionService,
			MigrationSourceService migrationSourceService) {
		this.migrationMappingService = migrationMappingService;
		this.migrationDefinitionService = migrationDefinitionService;
		this.migrationSourceService = migrationSourceService;
	}

	@Transactional
	public CompositeMigrationConfigurationResponse create(CompositeMigrationConfigurationRequest request) {
		MigrationMappingResponse mapping = migrationMappingService.create(request.mapping());
		List<MigrationMappingRuleResponse> rules = new ArrayList<>();
		for (CreateMappingRuleRequest rule : request.rules()) {
			rules.add(migrationMappingService.createRule(mapping.id(), rule));
		}
		MigrationDefinitionResponse definition = migrationDefinitionService.create(
				request.definition().toCreateRequest(mapping.id()));
		MigrationSourceResponse source = migrationSourceService.create(definition.id(), request.source());
		return new CompositeMigrationConfigurationResponse(
				definition,
				mapping,
				List.copyOf(rules),
				List.of(source));
	}
}
