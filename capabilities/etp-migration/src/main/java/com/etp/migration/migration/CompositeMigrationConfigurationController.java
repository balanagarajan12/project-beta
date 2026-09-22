package com.etp.migration.migration;

import com.etp.migration.migration.dto.CompositeMigrationConfigurationRequest;
import com.etp.migration.migration.dto.CompositeMigrationConfigurationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/migration-configurations")
@Tag(name = "Migration Configurations", description = "Product-level facade to create a complete migration configuration")
public class CompositeMigrationConfigurationController {

	private final CompositeMigrationConfigurationService compositeMigrationConfigurationService;

	public CompositeMigrationConfigurationController(
			CompositeMigrationConfigurationService compositeMigrationConfigurationService) {
		this.compositeMigrationConfigurationService = compositeMigrationConfigurationService;
	}

	@Operation(
			summary = "Create a complete migration configuration",
			description = "Creates mapping, mapping rules, a DRAFT migration definition, and one source in a single transaction. Does not activate the definition or start a run.")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Complete configuration created"),
			@ApiResponse(responseCode = "400", description = "Validation failed or promotion configuration is invalid"),
			@ApiResponse(responseCode = "409", description = "Definition, mapping, or rule uniqueness conflict")
	})
	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<CompositeMigrationConfigurationResponse> create(
			@Valid @RequestBody CompositeMigrationConfigurationRequest request) {
		CompositeMigrationConfigurationResponse created = compositeMigrationConfigurationService.create(request);
		return ResponseEntity
				.created(URI.create("/api/v1/migrations/" + created.definition().id()))
				.body(created);
	}
}
