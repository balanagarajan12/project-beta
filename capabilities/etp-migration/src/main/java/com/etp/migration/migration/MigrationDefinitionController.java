package com.etp.migration.migration;

import com.etp.migration.migration.dto.CreateMigrationDefinitionRequest;
import com.etp.migration.migration.dto.MigrationDefinitionResponse;
import com.etp.migration.migration.dto.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/migrations")
@Tag(name = "Migration Definitions", description = "Control-plane APIs for reusable migration definitions")
public class MigrationDefinitionController {

	private final MigrationDefinitionService migrationDefinitionService;

	public MigrationDefinitionController(MigrationDefinitionService migrationDefinitionService) {
		this.migrationDefinitionService = migrationDefinitionService;
	}

	@Operation(
			summary = "Create a migration definition",
			description = "Creates a DRAFT migration definition. Does not start a migration run.")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Definition created"),
			@ApiResponse(responseCode = "400", description = "Validation failed or mapping does not exist"),
			@ApiResponse(responseCode = "409", description = "Name and version already exist")
	})
	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<MigrationDefinitionResponse> create(
			@Valid @RequestBody CreateMigrationDefinitionRequest request) {
		MigrationDefinitionResponse created = migrationDefinitionService.create(request);
		return ResponseEntity
				.created(URI.create("/api/v1/migrations/" + created.id()))
				.body(created);
	}

	@Operation(
			summary = "List migration definitions",
			description = "Returns a paginated list of migration definitions. Maximum page size is 100.")
	@ApiResponse(responseCode = "200", description = "Page of definitions")
	@GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	public PagedResponse<MigrationDefinitionResponse> list(
			@RequestParam(name = "page", defaultValue = "0") int page,
			@RequestParam(name = "size", defaultValue = "20") int size) {
		return migrationDefinitionService.list(page, size);
	}

	@Operation(
			summary = "Get a migration definition",
			description = "Returns one migration definition by identifier.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Definition found"),
			@ApiResponse(responseCode = "404", description = "Definition not found")
	})
	@GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
	public MigrationDefinitionResponse getById(@PathVariable("id") UUID id) {
		return migrationDefinitionService.getById(id);
	}

	@Operation(
			summary = "Activate a migration definition",
			description = "Transitions a structurally complete DRAFT definition to ACTIVE and marks other ACTIVE versions of the same name INACTIVE. Already ACTIVE definitions are returned unchanged. Does not execute, promote, or reconcile.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Definition is ACTIVE"),
			@ApiResponse(responseCode = "400", description = "Definition is incomplete or invalid"),
			@ApiResponse(responseCode = "404", description = "Definition not found"),
			@ApiResponse(responseCode = "409", description = "Definition is not in a state that can be activated")
	})
	@PostMapping(value = "/{migrationId}/activate", produces = MediaType.APPLICATION_JSON_VALUE)
	public MigrationDefinitionResponse activate(@PathVariable("migrationId") UUID migrationId) {
		return migrationDefinitionService.activate(migrationId);
	}
}
