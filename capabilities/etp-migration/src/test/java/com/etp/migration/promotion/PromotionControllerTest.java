package com.etp.migration.promotion;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.etp.migration.config.GlobalExceptionHandler;
import com.etp.migration.entity.PromotionBatchStatus;
import com.etp.migration.entity.PromotionStatus;
import com.etp.migration.promotion.dto.ExecutePromotionResponse;
import com.etp.migration.promotion.dto.PreparePromotionResponse;
import com.etp.migration.entity.MigrationBatchStatus;
import com.etp.migration.promotion.dto.PromotionBatchEligibilityResponse;
import com.etp.migration.promotion.dto.PromotionBatchExecutionResponse;
import com.etp.migration.run.MigrationRunNotFoundException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = PromotionController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class PromotionControllerTest {

	private static final UUID RUN_ID = UUID.fromString("87492c12-d598-434d-b0ea-58da2e2e0b44");
	private static final UUID PROMOTION_ID = UUID.fromString("89b012a6-a857-41b0-be96-168e4de139da");

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PromotionQueryService promotionQueryService;

	@MockitoBean
	private com.etp.migration.run.MigrationPhaseSubmissionService phaseSubmissionService;

	@MockitoBean
	private com.etp.migration.run.ExecutionControlService executionControlService;

	@MockitoBean
	private com.etp.migration.run.MigrationPhaseCoordinator phaseCoordinator;

	@Test
	void getPromotionReturnsPersistedState() throws Exception {
		when(promotionQueryService.get(RUN_ID)).thenReturn(executeResponse(PromotionStatus.PROMOTED));

		mockMvc.perform(get("/api/v1/migration-runs/{runId}/promotion", RUN_ID)
						.accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.promotionId").value(PROMOTION_ID.toString()))
				.andExpect(jsonPath("$.runId").value(RUN_ID.toString()))
				.andExpect(jsonPath("$.status").value("PROMOTED"))
				.andExpect(jsonPath("$.promotedBatches").value(1))
				.andExpect(jsonPath("$.batches[0].batchId").value(1664))
				.andExpect(jsonPath("$.batches[0].promotionStatus").value("PROMOTED"));

		verify(phaseSubmissionService, never()).submitPreparation(any());
		verify(phaseSubmissionService, never()).submitPromotion(any());
	}

	@Test
	void getPromotionWhenMissingReturnsNotFound() throws Exception {
		when(promotionQueryService.get(RUN_ID)).thenThrow(new PromotionNotFoundException(RUN_ID));

		mockMvc.perform(get("/api/v1/migration-runs/{runId}/promotion", RUN_ID))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.message").value("Promotion not found for migration run " + RUN_ID));
	}

	@Test
	void getPromotionWhenRunMissingReturnsNotFound() throws Exception {
		when(promotionQueryService.get(RUN_ID)).thenThrow(new MigrationRunNotFoundException(RUN_ID));

		mockMvc.perform(get("/api/v1/migration-runs/{runId}/promotion", RUN_ID))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.message").value("Migration run not found: " + RUN_ID));
	}

	@Test
	void prepareAcceptsABackgroundJob() throws Exception {
		when(phaseSubmissionService.submitPreparation(RUN_ID)).thenReturn(accepted("PROMOTION_PREPARATION"));

		mockMvc.perform(post("/api/v1/migration-runs/{runId}/promotion/prepare", RUN_ID)
						.contentType(MediaType.APPLICATION_JSON))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("QUEUED"))
				.andExpect(jsonPath("$.phase").value("PROMOTION_PREPARATION"))
				.andExpect(jsonPath("$.runId").value(RUN_ID.toString()));
	}

	@Test
	void promoteAcceptsABackgroundJob() throws Exception {
		when(phaseSubmissionService.submitPromotion(RUN_ID)).thenReturn(accepted("PROMOTION"));

		mockMvc.perform(post("/api/v1/migration-runs/{runId}/promote", RUN_ID))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("QUEUED"))
				.andExpect(jsonPath("$.phase").value("PROMOTION"));
	}

	private static com.etp.migration.run.dto.PhaseAcceptanceResponse accepted(String phase) {
		return new com.etp.migration.run.dto.PhaseAcceptanceResponse(
				PROMOTION_ID,
				RUN_ID,
				null,
				com.etp.migration.run.MigrationPhase.valueOf(phase),
				com.etp.migration.run.PhaseJobStatus.QUEUED,
				null,
				"Phase accepted.",
				null,
				null,
				"/api/v1/migration-runs/" + RUN_ID + "/promotion");
	}

	private static ExecutePromotionResponse executeResponse(PromotionStatus status) {
		return new ExecutePromotionResponse(
				PROMOTION_ID,
				RUN_ID,
				status,
				1,
				1,
				1,
				0,
				0,
				3,
				3,
				0,
				0,
				List.of(new PromotionBatchExecutionResponse(
						1664L,
						PromotionBatchStatus.PROMOTED,
						3,
						3,
						0,
						null,
						null)));
	}

	private static PreparePromotionResponse prepareResponse() {
		return new PreparePromotionResponse(
				PROMOTION_ID,
				RUN_ID,
				PromotionStatus.READY,
				1,
				1,
				0,
				3,
				3,
				0,
				List.of(new PromotionBatchEligibilityResponse(
						1664L,
						MigrationBatchStatus.COMPLETED,
						PromotionBatchStatus.ELIGIBLE,
						3,
						0)));
	}
}
