package com.etp.migration.transformation;

import com.etp.migration.migration.dto.PagedResponse;
import com.etp.migration.transformation.dto.CreateMappingRuleRequest;
import com.etp.migration.transformation.dto.CreateMigrationMappingRequest;
import com.etp.migration.transformation.dto.MigrationMappingResponse;
import com.etp.migration.transformation.dto.MigrationMappingRuleResponse;
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
@RequestMapping("/api/v1/mappings")
@Tag(name = "Migration Mappings", description = "Reusable source-to-target mapping configuration")
public class MigrationMappingController {

	private final MigrationMappingService migrationMappingService;

	public MigrationMappingController(MigrationMappingService migrationMappingService) {
		this.migrationMappingService = migrationMappingService;
	}

	@Operation(
			summary = "Create a migration mapping",
			description = "Creates a DRAFT mapping definition. Does not transform data.")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Mapping created"),
			@ApiResponse(responseCode = "400", description = "Validation failed"),
			@ApiResponse(responseCode = "409", description = "Name and version already exist")
	})
	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<MigrationMappingResponse> create(
			@Valid @RequestBody CreateMigrationMappingRequest request) {
		MigrationMappingResponse created = migrationMappingService.create(request);
		return ResponseEntity
				.created(URI.create("/api/v1/mappings/" + created.id()))
				.body(created);
	}

	@Operation(
			summary = "List migration mappings",
			description = "Returns a paginated list of mapping definitions. Maximum page size is 100.")
	@ApiResponse(responseCode = "200", description = "Page of mappings")
	@GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	public PagedResponse<MigrationMappingResponse> list(
			@RequestParam(name = "page", defaultValue = "0") int page,
			@RequestParam(name = "size", defaultValue = "20") int size) {
		return migrationMappingService.list(page, size);
	}

	@Operation(summary = "Get a migration mapping", description = "Returns one mapping definition without its rules.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Mapping found"),
			@ApiResponse(responseCode = "404", description = "Mapping not found")
	})
	@GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
	public MigrationMappingResponse getById(@PathVariable("id") UUID id) {
		return migrationMappingService.getById(id);
	}

	@Operation(
			summary = "Create a mapping rule",
			description = "Adds a metadata-driven transformation rule. Expressions are not executed in this phase.")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Rule created"),
			@ApiResponse(responseCode = "400", description = "Validation failed"),
			@ApiResponse(responseCode = "404", description = "Mapping not found"),
			@ApiResponse(responseCode = "409", description = "Sequence number already exists")
	})
	@PostMapping(
			value = "/{mappingId}/rules",
			consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<MigrationMappingRuleResponse> createRule(
			@PathVariable("mappingId") UUID mappingId,
			@Valid @RequestBody CreateMappingRuleRequest request) {
		MigrationMappingRuleResponse created = migrationMappingService.createRule(mappingId, request);
		return ResponseEntity
				.created(URI.create("/api/v1/mappings/" + mappingId + "/rules/" + created.id()))
				.body(created);
	}

	@Operation(
			summary = "List mapping rules",
			description = "Returns a paginated list of mapping rules ordered by sequence number.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Page of rules"),
			@ApiResponse(responseCode = "404", description = "Mapping not found")
	})
	@GetMapping(value = "/{mappingId}/rules", produces = MediaType.APPLICATION_JSON_VALUE)
	public PagedResponse<MigrationMappingRuleResponse> listRules(
			@PathVariable("mappingId") UUID mappingId,
			@RequestParam(name = "page", defaultValue = "0") int page,
			@RequestParam(name = "size", defaultValue = "20") int size) {
		return migrationMappingService.listRules(mappingId, page, size);
	}
}
