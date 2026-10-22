package com.etp.migration.run;

import com.etp.migration.run.dto.CreateMigrationRunRequest;
import com.etp.migration.run.dto.MigrationExecutionResponse;
import com.etp.migration.run.dto.MigrationRunResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/migrations")
@Tag(name = "Migration Execution", description = "Product-level facade to create, prepare, and process a migration run in one call")
public class MigrationExecutionController {

	private final MigrationExecutionService migrationExecutionService;

	public MigrationExecutionController(MigrationExecutionService migrationExecutionService) {
		this.migrationExecutionService = migrationExecutionService;
	}

	@Operation(
			summary = "Execute a migration",
			description = "Creates a QUEUED run for an ACTIVE definition and accepts it for background preparation and batch processing. Returns before that work finishes. Does not activate the definition, promote, or reconcile. Poll GET /api/v1/migration-runs/{runId} for progress.")
	@ApiResponses({
			@ApiResponse(responseCode = "202", description = "Run accepted. Body is the acceptance snapshot, not the finished run."),
			@ApiResponse(responseCode = "404", description = "Migration definition not found"),
			@ApiResponse(responseCode = "409", description = "Definition is not ACTIVE")
	})
	@PostMapping(
			value = "/{migrationId}/execute",
			consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<MigrationExecutionResponse> execute(
			@PathVariable("migrationId") UUID migrationId,
			@Valid @RequestBody(required = false) CreateMigrationRunRequest request) {
		MigrationRunResponse accepted = migrationExecutionService.execute(migrationId, request);
		MigrationExecutionResponse body = MigrationExecutionResponse.from(accepted);
		return ResponseEntity.accepted()
				.location(URI.create(body.statusUrl()))
				.body(body);
	}
}
