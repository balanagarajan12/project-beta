package com.etp.migration.promotion;

import com.etp.migration.migration.dto.PagedResponse;
import com.etp.migration.promotion.dto.ExecutePromotionResponse;
import com.etp.migration.promotion.dto.PreparePromotionRequest;
import com.etp.migration.promotion.dto.PromotionBatchDetailResponse;
import com.etp.migration.run.ExecutionControlService;
import com.etp.migration.run.ExecutionControlState;
import com.etp.migration.run.MigrationPhaseCoordinator;
import com.etp.migration.run.MigrationPhaseSubmissionService;
import com.etp.migration.run.dto.ExecutionControlRequest;
import com.etp.migration.run.dto.ExecutionControlResponse;
import com.etp.migration.run.dto.PhaseAcceptanceResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Migration Promotion", description = "Explicit promotion preparation and execution. Execution writes to the configured PostgreSQL business table.")
public class PromotionController {

	private final PromotionQueryService promotionQueryService;
	private final MigrationPhaseSubmissionService phaseSubmissionService;
	private final ExecutionControlService executionControlService;
	private final MigrationPhaseCoordinator phaseCoordinator;

	public PromotionController(
			PromotionQueryService promotionQueryService,
			MigrationPhaseSubmissionService phaseSubmissionService,
			ExecutionControlService executionControlService,
			MigrationPhaseCoordinator phaseCoordinator) {
		this.promotionQueryService = promotionQueryService;
		this.phaseSubmissionService = phaseSubmissionService;
		this.executionControlService = executionControlService;
		this.phaseCoordinator = phaseCoordinator;
	}

	@Operation(
			summary = "Prepare a promotion",
			description = "Accepts promotion preparation after execution and reconciliation. Returns before eligible batches are counted. Does not write business tables. Poll GET /api/v1/migration-runs/{runId}/promotion. A queued or running preparation is not started again. An existing READY or PROMOTED promotion is left unchanged.")
	@ApiResponses({
			@ApiResponse(responseCode = "202", description = "Preparation accepted"),
			@ApiResponse(responseCode = "400", description = "Promotion is missing, disabled, invalid, or the target is unsupported"),
			@ApiResponse(responseCode = "404", description = "Migration run not found"),
			@ApiResponse(responseCode = "409", description = "Run is not ready for promotion preparation")
	})
	@PostMapping(
			value = "/migration-runs/{runId}/promotion/prepare",
			produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<PhaseAcceptanceResponse> prepare(
			@PathVariable("runId") UUID runId,
			@RequestBody(required = false) PreparePromotionRequest request) {
		PhaseAcceptanceResponse body = phaseSubmissionService.submitPreparation(runId);
		return ResponseEntity.accepted().location(URI.create(body.statusUrl())).body(body);
	}

	@Operation(
			summary = "Execute a prepared promotion",
			description = "Accepts promotion of an existing READY promotion into the configured PostgreSQL business table. Does not prepare one when none exists. Returns before business rows are written. Poll GET /api/v1/migration-runs/{runId}/promotion. A second call while the job is queued or running returns that job.")
	@ApiResponses({
			@ApiResponse(responseCode = "202", description = "Promotion accepted"),
			@ApiResponse(responseCode = "400", description = "Promotion is missing, disabled, invalid, or the target is unsupported"),
			@ApiResponse(responseCode = "404", description = "Migration run not found"),
			@ApiResponse(responseCode = "409", description = "Promotion is not READY or the run is not eligible")
	})
	@PostMapping(value = "/migration-runs/{runId}/promote", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<PhaseAcceptanceResponse> promote(@PathVariable("runId") UUID runId) {
		PhaseAcceptanceResponse body = phaseSubmissionService.submitPromotion(runId);
		return ResponseEntity.accepted().location(URI.create(body.statusUrl())).body(body);
	}

	@Operation(
			summary = "Get promotion status",
			description = "Returns the persisted promotion and batch state for a run. Does not prepare, promote, or change any records.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Promotion found"),
			@ApiResponse(responseCode = "404", description = "Migration run or promotion not found")
	})
	@GetMapping(value = "/migration-runs/{runId}/promotion", produces = MediaType.APPLICATION_JSON_VALUE)
	public ExecutePromotionResponse get(@PathVariable("runId") UUID runId) {
		return promotionQueryService.get(runId);
	}

	@Operation(
			summary = "List promotion batches",
			description = "Paginated promotion batches for a run. Does not load staging payloads or source locators. durationMillis is the latest attempt and is calculated when the response is built. An active batch has a null completedAt.")
	@GetMapping(value = "/migration-runs/{runId}/promotion/batches", produces = MediaType.APPLICATION_JSON_VALUE)
	public PagedResponse<PromotionBatchDetailResponse> listBatches(
			@PathVariable("runId") UUID runId,
			@RequestParam(name = "page", defaultValue = "0") int page,
			@RequestParam(name = "size", defaultValue = "20") int size,
			@RequestParam(name = "status", required = false) String status) {
		return promotionQueryService.listBatches(runId, page, size, status);
	}

	@Operation(
			summary = "Pause or resume promotion",
			description = "Independent of migration-run control. PAUSE stops claiming further promotion batches after the current batch commits. RESUME claims the existing promotion job and does not repeat promoted batches. Startup does not resume a paused promotion.")
	@PostMapping(value = "/migration-runs/{runId}/promotion/control", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ExecutionControlResponse control(
			@PathVariable("runId") UUID runId,
			@RequestBody ExecutionControlRequest request) {
		String action = request == null || request.action() == null ? "" : request.action().trim();
		if (!"PAUSE".equals(action) && !"RESUME".equals(action)) {
			throw new IllegalArgumentException("Control action must be PAUSE or RESUME");
		}
		ExecutionControlResponse body = "PAUSE".equals(action)
				? executionControlService.pausePromotion(runId)
				: executionControlService.resumePromotion(runId);
		if ("RESUME".equals(action) && body.resumeJobId() != null && body.controlState() == ExecutionControlState.RESUMING) {
			phaseCoordinator.submit(body.resumeJobId());
		}
		return body;
	}
}
