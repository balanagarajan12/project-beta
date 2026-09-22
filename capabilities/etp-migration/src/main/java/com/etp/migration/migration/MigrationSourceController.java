package com.etp.migration.migration;

import com.etp.migration.migration.dto.CreateMigrationSourceRequest;
import com.etp.migration.migration.dto.MigrationSourceResponse;
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
@RequestMapping("/api/v1/migrations/{migrationId}/sources")
@Tag(name = "Migration Sources", description = "Source configuration for a migration definition")
public class MigrationSourceController {

	private final MigrationSourceService migrationSourceService;

	public MigrationSourceController(MigrationSourceService migrationSourceService) {
		this.migrationSourceService = migrationSourceService;
	}

	@Operation(
			summary = "Create a migration source",
			description = "Attaches a source configuration to a migration definition. Does not extract data.")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Source created"),
			@ApiResponse(responseCode = "400", description = "Validation failed"),
			@ApiResponse(responseCode = "404", description = "Migration definition not found")
	})
	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<MigrationSourceResponse> create(
			@PathVariable("migrationId") UUID migrationId,
			@Valid @RequestBody CreateMigrationSourceRequest request) {
		MigrationSourceResponse created = migrationSourceService.create(migrationId, request);
		return ResponseEntity
				.created(URI.create("/api/v1/migrations/" + migrationId + "/sources/" + created.id()))
				.body(created);
	}

	@Operation(
			summary = "List migration sources",
			description = "Returns a paginated list of sources for a migration definition.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Page of sources"),
			@ApiResponse(responseCode = "404", description = "Migration definition not found")
	})
	@GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	public PagedResponse<MigrationSourceResponse> list(
			@PathVariable("migrationId") UUID migrationId,
			@RequestParam(name = "page", defaultValue = "0") int page,
			@RequestParam(name = "size", defaultValue = "20") int size) {
		return migrationSourceService.list(migrationId, page, size);
	}

	@Operation(summary = "Get a migration source", description = "Returns one source for a migration definition.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Source found"),
			@ApiResponse(responseCode = "404", description = "Migration definition or source not found")
	})
	@GetMapping(value = "/{sourceId}", produces = MediaType.APPLICATION_JSON_VALUE)
	public MigrationSourceResponse getById(
			@PathVariable("migrationId") UUID migrationId,
			@PathVariable("sourceId") UUID sourceId) {
		return migrationSourceService.getById(migrationId, sourceId);
	}
}
