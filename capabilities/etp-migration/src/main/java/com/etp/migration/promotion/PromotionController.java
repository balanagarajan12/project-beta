package com.etp.migration.promotion;

import com.etp.migration.promotion.dto.ExecutePromotionResponse;
import com.etp.migration.promotion.dto.PreparePromotionRequest;
import com.etp.migration.promotion.dto.PreparePromotionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Migration Promotion", description = "Explicit promotion preparation and execution. Execution writes to the configured PostgreSQL business table.")
public class PromotionController {

	private final PromotionPreparationService promotionPreparationService;
	private final PromotionExecutionService promotionExecutionService;
	private final PromotionQueryService promotionQueryService;

	public PromotionController(
			PromotionPreparationService promotionPreparationService,
			PromotionExecutionService promotionExecutionService,
			PromotionQueryService promotionQueryService) {
		this.promotionPreparationService = promotionPreparationService;
		this.promotionExecutionService = promotionExecutionService;
		this.promotionQueryService = promotionQueryService;
	}

	@Operation(
			summary = "Prepare a promotion",
			description = "Identifies eligible staged batches after execution and reconciliation. Does not promote records into the application database.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Promotion prepared or existing promotion returned"),
			@ApiResponse(responseCode = "400", description = "Promotion is missing, disabled, invalid, or the target is unsupported"),
			@ApiResponse(responseCode = "404", description = "Migration run not found"),
			@ApiResponse(responseCode = "409", description = "Run is not ready for promotion preparation")
	})
	@PostMapping(
			value = "/migration-runs/{runId}/promotion/prepare",
			produces = MediaType.APPLICATION_JSON_VALUE)
	public PreparePromotionResponse prepare(
			@PathVariable("runId") UUID runId,
			@RequestBody(required = false) PreparePromotionRequest request) {
		return promotionPreparationService.prepare(runId, request);
	}

	@Operation(
			summary = "Execute a prepared promotion",
			description = "Promotes eligible staged rows into the configured PostgreSQL business table. Requires an existing READY promotion. Does not auto-prepare.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Promotion executed or existing PROMOTED result returned"),
			@ApiResponse(responseCode = "400", description = "Promotion is missing, disabled, invalid, or the target is unsupported"),
			@ApiResponse(responseCode = "404", description = "Migration run not found"),
			@ApiResponse(responseCode = "409", description = "Promotion is not READY, already in progress, or the run is not eligible")
	})
	@PostMapping(value = "/migration-runs/{runId}/promote", produces = MediaType.APPLICATION_JSON_VALUE)
	public ExecutePromotionResponse promote(@PathVariable("runId") UUID runId) {
		return promotionExecutionService.promote(runId);
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
}
