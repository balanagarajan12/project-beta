package com.etp.migration.run;

import com.etp.migration.batch.dto.MigrationBatchResponse;
import com.etp.migration.migration.dto.PagedResponse;
import com.etp.migration.processing.PrepareMigrationRunResponse;
import com.etp.migration.processing.ProcessBatchResponse;
import com.etp.migration.processing.MigrationBatchWorker;
import com.etp.migration.processing.SourceInspectionService;
import com.etp.migration.reconciliation.ReconciliationService;
import com.etp.migration.reconciliation.dto.MigrationReconciliationResponse;
import com.etp.migration.run.dto.CreateMigrationRunRequest;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Migration Runs", description = "Execution control plane for migration runs and batches")
public class MigrationRunController {

	private final MigrationRunService migrationRunService;
	private final SourceInspectionService sourceInspectionService;
	private final MigrationBatchWorker migrationBatchWorker;
	private final ReconciliationService reconciliationService;

	public MigrationRunController(
			MigrationRunService migrationRunService,
			SourceInspectionService sourceInspectionService,
			MigrationBatchWorker migrationBatchWorker,
			ReconciliationService reconciliationService) {
		this.migrationRunService = migrationRunService;
		this.sourceInspectionService = sourceInspectionService;
		this.migrationBatchWorker = migrationBatchWorker;
		this.reconciliationService = reconciliationService;
	}

	@Operation(summary = "Create a migration run", description = "Creates a QUEUED run for an ACTIVE definition. Does not process source data.")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Run created or existing idempotent run returned"),
			@ApiResponse(responseCode = "404", description = "Migration definition not found"),
			@ApiResponse(responseCode = "409", description = "Definition is not ACTIVE")
	})
	@PostMapping(
			value = "/migrations/{migrationId}/runs",
			consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<MigrationRunResponse> create(
			@PathVariable("migrationId") UUID migrationId,
			@Valid @RequestBody(required = false) CreateMigrationRunRequest request) {
		MigrationRunResponse created = migrationRunService.create(
				migrationId,
				request == null ? new CreateMigrationRunRequest(null, null, null) : request);
		return ResponseEntity
				.created(URI.create("/api/v1/migration-runs/" + created.id()))
				.body(created);
	}

	@Operation(summary = "List migration runs", description = "Paginated runs for a definition, newest first.")
	@GetMapping(value = "/migrations/{migrationId}/runs", produces = MediaType.APPLICATION_JSON_VALUE)
	public PagedResponse<MigrationRunResponse> list(
			@PathVariable("migrationId") UUID migrationId,
			@RequestParam(name = "page", defaultValue = "0") int page,
			@RequestParam(name = "size", defaultValue = "20") int size) {
		return migrationRunService.list(migrationId, page, size);
	}

	@Operation(summary = "Get a migration run", description = "Returns run-level counters and status only.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Run found"),
			@ApiResponse(responseCode = "404", description = "Run not found")
	})
	@GetMapping(value = "/migration-runs/{runId}", produces = MediaType.APPLICATION_JSON_VALUE)
	public MigrationRunResponse getById(@PathVariable("runId") UUID runId) {
		return migrationRunService.getById(runId);
	}

	@Operation(summary = "Cancel a migration run", description = "Requests cancellation. In-flight claimed batches are not deleted.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Cancellation requested or completed"),
			@ApiResponse(responseCode = "409", description = "Run is already terminal")
	})
	@PostMapping(value = "/migration-runs/{runId}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
	public MigrationRunResponse cancel(@PathVariable("runId") UUID runId) {
		return migrationRunService.cancel(runId);
	}

	@Operation(summary = "Retry a migration run", description = "Re-queues failed or completed-with-errors batches. Successful batches are not duplicated.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Retryable batches re-queued"),
			@ApiResponse(responseCode = "409", description = "Run cannot be retried")
	})
	@PostMapping(value = "/migration-runs/{runId}/retry", produces = MediaType.APPLICATION_JSON_VALUE)
	public MigrationRunResponse retry(@PathVariable("runId") UUID runId) {
		return migrationRunService.retry(runId);
	}

	@Operation(summary = "Prepare a migration run", description = "Inspects the configured source, persists metadata, and creates batches. Does not start processing.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Run prepared or existing preparation returned"),
			@ApiResponse(responseCode = "404", description = "Run, source, or artifact not found"),
			@ApiResponse(responseCode = "409", description = "Run is not QUEUED")
	})
	@PostMapping(value = "/migration-runs/{runId}/prepare", produces = MediaType.APPLICATION_JSON_VALUE)
	public PrepareMigrationRunResponse prepare(@PathVariable("runId") UUID runId) {
		return sourceInspectionService.prepare(runId);
	}

	@Operation(summary = "Process next eligible batch", description = "Claims and processes one batch. Development/testing endpoint; not a scheduler.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Batch processed"),
			@ApiResponse(responseCode = "409", description = "No eligible batch")
	})
	@PostMapping(value = "/migration-runs/{runId}/process-next-batch", produces = MediaType.APPLICATION_JSON_VALUE)
	public ProcessBatchResponse processNextBatch(@PathVariable("runId") UUID runId) {
		return migrationBatchWorker.processNextBatchOrConflict(runId);
	}

	@Operation(summary = "Reconcile a migration run", description = "Verifies source, processing, and target counts after all batches are terminal. Default target scope is STAGING. Pass verificationScope=BUSINESS_TARGET after promotion to verify the application table. Does not change run or promotion status. Safe to repeat; one row is stored per reconciliation type.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Reconciliation completed"),
			@ApiResponse(responseCode = "404", description = "Run not found"),
			@ApiResponse(responseCode = "409", description = "Run still has active batches"),
			@ApiResponse(responseCode = "500", description = "Unexpected reconciliation failure")
	})
	@PostMapping(value = "/migration-runs/{runId}/reconcile", produces = MediaType.APPLICATION_JSON_VALUE)
	public MigrationReconciliationResponse reconcile(
			@PathVariable("runId") UUID runId,
			@RequestParam(name = "verificationScope", required = false) String verificationScope) {
		if (verificationScope == null || verificationScope.isBlank()) {
			return reconciliationService.reconcile(runId);
		}
		return reconciliationService.reconcile(runId, verificationScope);
	}

	@Operation(summary = "Get migration reconciliation", description = "Returns the latest COUNT, CHECKSUM, TARGET, and FULL reconciliation records for the run.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Reconciliation found"),
			@ApiResponse(responseCode = "404", description = "Run or reconciliation not found")
	})
	@GetMapping(value = "/migration-runs/{runId}/reconciliation", produces = MediaType.APPLICATION_JSON_VALUE)
	public MigrationReconciliationResponse getReconciliation(@PathVariable("runId") UUID runId) {
		return reconciliationService.get(runId);
	}

	@Operation(summary = "List migration batches", description = "Paginated batch control-plane rows for a run.")
	@GetMapping(value = "/migration-runs/{runId}/batches", produces = MediaType.APPLICATION_JSON_VALUE)
	public PagedResponse<MigrationBatchResponse> listBatches(
			@PathVariable("runId") UUID runId,
			@RequestParam(name = "page", defaultValue = "0") int page,
			@RequestParam(name = "size", defaultValue = "20") int size) {
		return migrationRunService.listBatches(runId, page, size);
	}
}
