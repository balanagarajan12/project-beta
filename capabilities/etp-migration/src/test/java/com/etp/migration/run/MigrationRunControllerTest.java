package com.etp.migration.run;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.etp.migration.config.GlobalExceptionHandler;
import com.etp.migration.entity.MigrationConfigStatus;
import com.etp.migration.entity.MigrationRunStatus;
import com.etp.migration.migration.dto.PagedResponse;
import com.etp.migration.processing.MigrationBatchWorker;
import com.etp.migration.processing.SourceInspectionService;
import com.etp.migration.reconciliation.ReconciliationService;
import com.etp.migration.run.dto.MigrationRunResponse;
import java.time.Instant;
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

@WebMvcTest(controllers = MigrationRunController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class MigrationRunControllerTest {

	private static final UUID DEFINITION_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
	private static final UUID RUN_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private MigrationRunService migrationRunService;

	@MockitoBean
	private SourceInspectionService sourceInspectionService;

	@MockitoBean
	private MigrationBatchWorker migrationBatchWorker;

	@MockitoBean
	private ReconciliationService reconciliationService;

	@Test
	void createRunReturnsCreated() throws Exception {
		when(migrationRunService.create(eq(DEFINITION_ID), any())).thenReturn(sampleRun(MigrationRunStatus.QUEUED));

		mockMvc.perform(post("/api/v1/migrations/{migrationId}/runs", DEFINITION_ID)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(RUN_ID.toString()))
				.andExpect(jsonPath("$.status").value("QUEUED"));
	}

	@Test
	void createRunRejectsInactiveDefinition() throws Exception {
		when(migrationRunService.create(eq(DEFINITION_ID), any()))
				.thenThrow(new DefinitionNotActiveException(DEFINITION_ID, MigrationConfigStatus.DRAFT));

		mockMvc.perform(post("/api/v1/migrations/{migrationId}/runs", DEFINITION_ID)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isConflict());
	}

	@Test
	void getUnknownRunReturnsNotFound() throws Exception {
		when(migrationRunService.getById(RUN_ID)).thenThrow(new MigrationRunNotFoundException(RUN_ID));

		mockMvc.perform(get("/api/v1/migration-runs/{runId}", RUN_ID))
				.andExpect(status().isNotFound());
	}

	@Test
	void listRunsReturnsPage() throws Exception {
		when(migrationRunService.list(DEFINITION_ID, 0, 20))
				.thenReturn(new PagedResponse<>(List.of(sampleRun(MigrationRunStatus.QUEUED)), 0, 20, 1, 1));

		mockMvc.perform(get("/api/v1/migrations/{migrationId}/runs", DEFINITION_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1));
	}

	@Test
	void cancelTerminalRunReturnsConflict() throws Exception {
		when(migrationRunService.cancel(RUN_ID))
				.thenThrow(new InvalidStateTransitionException("Cannot transition migration run from COMPLETED to CANCEL_REQUESTED"));

		mockMvc.perform(post("/api/v1/migration-runs/{runId}/cancel", RUN_ID))
				.andExpect(status().isConflict());
	}

	@Test
	void prepareUnknownRunReturnsNotFound() throws Exception {
		when(sourceInspectionService.prepare(RUN_ID)).thenThrow(new MigrationRunNotFoundException(RUN_ID));

		mockMvc.perform(post("/api/v1/migration-runs/{runId}/prepare", RUN_ID))
				.andExpect(status().isNotFound());
	}

	@Test
	void reconcileActiveRunReturnsConflict() throws Exception {
		when(reconciliationService.reconcile(RUN_ID))
				.thenThrow(new com.etp.migration.reconciliation.ReconciliationNotReadyException("Migration run still has active batches"));

		mockMvc.perform(post("/api/v1/migration-runs/{runId}/reconcile", RUN_ID))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("MIGRATION_RECONCILIATION_NOT_READY"));
	}

	private MigrationRunResponse sampleRun(MigrationRunStatus status) {
		Instant now = Instant.parse("2026-09-20T10:00:00Z");
		return new MigrationRunResponse(
				RUN_ID,
				DEFINITION_ID,
				1L,
				null,
				status,
				null,
				null,
				0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
				0, 0, 0,
				"system",
				now,
				now);
	}
}
