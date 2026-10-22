package com.etp.migration.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.etp.migration.config.GlobalExceptionHandler;
import com.etp.migration.connector.target.TargetFailureDiagnostics;
import com.etp.migration.connector.target.TargetInfrastructureException;
import com.etp.migration.entity.MigrationConfigStatus;
import com.etp.migration.entity.MigrationRunStatus;
import com.etp.migration.migration.dto.PagedResponse;
import com.etp.migration.processing.MigrationBatchWorker;
import com.etp.migration.processing.SourceInspectionService;
import com.etp.migration.reconciliation.ReconciliationService;
import com.etp.migration.run.dto.MigrationErrorResponse;
import com.etp.migration.run.dto.MigrationRunResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.slf4j.LoggerFactory;
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

	@MockitoBean
	private MigrationPhaseSubmissionService phaseSubmissionService;

	@MockitoBean
	private ExecutionControlService executionControlService;

	@MockitoBean
	private MigrationExecutionCoordinator executionCoordinator;

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
	void listErrorsReturnsStructuredDetails() throws Exception {
		when(migrationRunService.listErrors(RUN_ID, 0, 20)).thenReturn(new PagedResponse<>(
				List.of(new MigrationErrorResponse(
						9L,
						RUN_ID,
						1L,
						"LOAD",
						"MIGRATION_TARGET_COPY_FAILED",
						"BATCH_FAILED",
						null,
						null,
						"Target load failed",
						Map.of("sqlState", "42P01", "relation", "migration_staging.missing_load"),
						true,
						Instant.parse("2026-10-20T01:58:43Z"))),
				0,
				20,
				1,
				1));

		mockMvc.perform(get("/api/v1/migration-runs/{runId}/errors", RUN_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].errorCode").value("MIGRATION_TARGET_COPY_FAILED"))
				.andExpect(jsonPath("$.content[0].errorMessage").value("Target load failed"))
				.andExpect(jsonPath("$.content[0].errorDetails.sqlState").value("42P01"))
				.andExpect(jsonPath("$.content[0].errorDetails.relation").value("migration_staging.missing_load"));
	}

	@Test
	void getUnknownRunReturnsNotFound() throws Exception {
		when(migrationRunService.getById(RUN_ID)).thenThrow(new MigrationRunNotFoundException(RUN_ID));

		mockMvc.perform(get("/api/v1/migration-runs/{runId}", RUN_ID))
				.andExpect(status().isNotFound());
	}

	@Test
	void listAllRunsReturnsPage() throws Exception {
		when(migrationRunService.listAll(0, 20, null))
				.thenReturn(new PagedResponse<>(List.of(sampleRun(MigrationRunStatus.QUEUED)), 0, 20, 1, 1));

		mockMvc.perform(get("/api/v1/migration-runs").param("page", "0").param("size", "20"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].id").value(RUN_ID.toString()))
				.andExpect(jsonPath("$.content[0].migrationDefinitionId").value(DEFINITION_ID.toString()))
				.andExpect(jsonPath("$.content[0].status").value("QUEUED"))
				.andExpect(jsonPath("$.content[0].createdAt").value("2026-09-20T10:00:00Z"))
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(20))
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.totalPages").value(1));
	}

	@Test
	void listAllRunsReturnsEmptyPage() throws Exception {
		when(migrationRunService.listAll(0, 20, null))
				.thenReturn(new PagedResponse<>(List.of(), 0, 20, 0, 0));

		mockMvc.perform(get("/api/v1/migration-runs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content").isEmpty())
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(20))
				.andExpect(jsonPath("$.totalElements").value(0))
				.andExpect(jsonPath("$.totalPages").value(0));
	}

	@Test
	void listAllPassesPaginationParametersThrough() throws Exception {
		when(migrationRunService.listAll(-1, 500, null))
				.thenReturn(new PagedResponse<>(List.of(), 0, 100, 0, 0));

		mockMvc.perform(get("/api/v1/migration-runs").param("page", "-1").param("size", "500"))
				.andExpect(status().isOk());

		verify(migrationRunService).listAll(-1, 500, null);
	}

	@Test
	void listAllPassesDefinitionFilter() throws Exception {
		when(migrationRunService.listAll(0, 20, DEFINITION_ID))
				.thenReturn(new PagedResponse<>(List.of(sampleRun(MigrationRunStatus.QUEUED)), 0, 20, 1, 1));

		mockMvc.perform(get("/api/v1/migration-runs").param("migrationDefinitionId", DEFINITION_ID.toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].migrationDefinitionId").value(DEFINITION_ID.toString()));

		verify(migrationRunService).listAll(0, 20, DEFINITION_ID);
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
		when(phaseSubmissionService.submitReconciliation(RUN_ID, null))
				.thenThrow(new com.etp.migration.reconciliation.ReconciliationNotReadyException("Migration run still has active batches"));

		mockMvc.perform(post("/api/v1/migration-runs/{runId}/reconcile", RUN_ID))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("MIGRATION_RECONCILIATION_NOT_READY"));
	}

	@Test
	void malformedIdentifiersReturnBadRequest() throws Exception {
		mockMvc.perform(get("/api/v1/migration-runs/{runId}", "not-a-uuid"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message").value("Invalid value for parameter 'runId'"));
		mockMvc.perform(get("/api/v1/migration-runs").param("migrationDefinitionId", "not-a-uuid"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Invalid value for parameter 'migrationDefinitionId'"));
		mockMvc.perform(get("/api/v1/migration-runs").param("page", "first"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void frameworkClientErrorsKeepTheirStatus() throws Exception {
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
						.delete("/api/v1/migration-runs/{runId}", RUN_ID))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(jsonPath("$.status").value(405))
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
						.exists("Allow"));
		mockMvc.perform(post("/api/v1/migrations/{migrationId}/runs", DEFINITION_ID)
						.contentType(MediaType.TEXT_PLAIN)
						.content("x"))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.status").value(415));
		mockMvc.perform(get("/api/v1/migration-runs/{runId}/unknown", RUN_ID))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404));
	}

	@Test
	void unexpectedFailureReturnsGenericServerErrorWithoutInternalDetail() throws Exception {
		when(migrationRunService.getById(RUN_ID)).thenThrow(new IllegalStateException("jdbc:postgresql://internal-host"));

		mockMvc.perform(get("/api/v1/migration-runs/{runId}", RUN_ID))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.message").value("An unexpected error occurred"));
	}

	@Test
	void targetInfrastructureFailureReturnsGenericMessageAndLogsSanitizedReference() throws Exception {
		PSQLException database = new PSQLException(new ServerErrorMessage(
				"SERROR\0C23514\0Mnew row violates check constraint\0DFailing row contains (LOC-2, secret-value).\0"
						+ "suam\0tlocation\0nck_location\0"));
		when(phaseSubmissionService.submitReconciliation(RUN_ID, null)).thenThrow(new TargetInfrastructureException(
				"MIGRATION_TARGET_COPY_FAILED",
				"Canonical target load failed and was rolled back: " + TargetFailureDiagnostics.describe(database),
				database));
		Logger handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		handlerLogger.addAppender(appender);
		try {
			String body = mockMvc.perform(post("/api/v1/migration-runs/{runId}/reconcile", RUN_ID))
					.andExpect(status().isInternalServerError())
					.andExpect(jsonPath("$.code").value("MIGRATION_TARGET_COPY_FAILED"))
					.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.matchesPattern(
							"Target system operation failed \\(reference [0-9a-f-]{36}\\)")))
					.andReturn().getResponse().getContentAsString();
			assertThat(body).doesNotContain("secret-value", "ck_location", "Failing row", "rolled back");

			String reference = body.replaceAll("(?s).*reference ([0-9a-f-]{36}).*", "$1");
			assertThat(appender.list).singleElement().satisfies(event -> {
				String logged = event.getFormattedMessage();
				assertThat(event.getLevel()).isEqualTo(Level.ERROR);
				assertThat(logged).contains("reference=" + reference, "code=MIGRATION_TARGET_COPY_FAILED",
						"check_violation (SQLState 23514), table uam.location, constraint ck_location");
				assertThat(logged).doesNotContain("secret-value", "Failing row");
				assertThat(event.getThrowableProxy()).isNull();
			});
		} finally {
			handlerLogger.detachAppender(appender);
		}
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
