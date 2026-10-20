package com.etp.migration.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.etp.migration.config.ShutdownSignal;
import com.etp.migration.entity.MigrationConfigStatus;
import com.etp.migration.entity.MigrationRunStatus;
import com.etp.migration.migration.MigrationDefinitionNotFoundException;
import com.etp.migration.processing.MigrationBatchWorker;
import com.etp.migration.processing.PrepareMigrationRunResponse;
import com.etp.migration.processing.ProcessBatchResponse;
import com.etp.migration.processing.SourceInspectionService;
import com.etp.migration.run.dto.CreateMigrationRunRequest;
import com.etp.migration.run.dto.MigrationRunResponse;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class MigrationExecutionServiceTest {

	private static final UUID DEFINITION_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
	private static final UUID RUN_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

	@Mock
	private MigrationRunService migrationRunService;
	@Mock
	private SourceInspectionService sourceInspectionService;
	@Mock
	private MigrationBatchWorker migrationBatchWorker;
	@Mock
	private RunExecutionLifecycle runExecutionLifecycle;

	private final ShutdownSignal shutdownSignal = new ShutdownSignal();

	private MigrationExecutionService service;

	@BeforeEach
	void setUp() {
		service = new MigrationExecutionService(
				migrationRunService,
				sourceInspectionService,
				migrationBatchWorker,
				runExecutionLifecycle,
				shutdownSignal);
	}

	@AfterEach
	void neverTouchesPlanningRetryOrConflictApi() {
		verify(migrationRunService, never()).planBatches(any(), anyLong());
		verify(migrationRunService, never()).retry(any());
		verify(migrationBatchWorker, never()).processNextBatchOrConflict(any());
	}

	@Test
	void facadeHasNoTransactionalAnnotation() throws Exception {
		assertThat(MigrationExecutionService.class.getAnnotation(Transactional.class)).isNull();
		assertThat(MigrationExecutionService.class
				.getMethod("execute", UUID.class, CreateMigrationRunRequest.class)
				.getAnnotation(Transactional.class)).isNull();
	}

	@Test
	void zeroBatchesStartsExecutionAndReturnsTheFinalizedRun() {
		when(migrationRunService.create(eq(DEFINITION_ID), any())).thenReturn(run(MigrationRunStatus.QUEUED));
		when(sourceInspectionService.prepare(RUN_ID)).thenReturn(prepare(0));
		when(migrationBatchWorker.processNextBatch(RUN_ID)).thenReturn(Optional.empty());
		when(migrationRunService.getById(RUN_ID)).thenReturn(run(MigrationRunStatus.COMPLETED, 0, 0, 0));

		MigrationRunResponse response = service.execute(DEFINITION_ID, null);

		assertThat(response.status()).isEqualTo(MigrationRunStatus.COMPLETED);
		assertThat(response.totalBatches()).isZero();
		verify(runExecutionLifecycle).beginExecution(RUN_ID);
		verify(runExecutionLifecycle).finalizeIfIdle(RUN_ID);
		verifyOrder(0);
	}

	@Test
	void oneBatchCompletedReturnsFinalRun() {
		stubCreatePrepare();
		when(migrationBatchWorker.processNextBatch(RUN_ID))
				.thenReturn(Optional.of(batch("COMPLETED")))
				.thenReturn(Optional.empty());
		when(migrationRunService.getById(RUN_ID)).thenReturn(run(MigrationRunStatus.COMPLETED, 1, 1, 0));

		MigrationRunResponse response = service.execute(DEFINITION_ID, new CreateMigrationRunRequest(null, null, null));

		assertThat(response.status()).isEqualTo(MigrationRunStatus.COMPLETED);
		verifyOrder(1);
	}

	@Test
	void multipleBatchesAreProcessedUntilEmpty() {
		stubCreatePrepare();
		when(migrationBatchWorker.processNextBatch(RUN_ID))
				.thenReturn(Optional.of(batch("COMPLETED")))
				.thenReturn(Optional.of(batch("COMPLETED")))
				.thenReturn(Optional.empty());
		when(migrationRunService.getById(RUN_ID)).thenReturn(run(MigrationRunStatus.COMPLETED, 2, 2, 0));

		assertThat(service.execute(DEFINITION_ID, null).status()).isEqualTo(MigrationRunStatus.COMPLETED);
		verify(migrationBatchWorker, times(3)).processNextBatch(RUN_ID);
		verifyOrder(2);
	}

	@Test
	void completedWithErrorsIsReturnedAsIs() {
		stubCreatePrepare();
		when(migrationBatchWorker.processNextBatch(RUN_ID))
				.thenReturn(Optional.of(batch("COMPLETED_WITH_ERRORS")))
				.thenReturn(Optional.empty());
		when(migrationRunService.getById(RUN_ID)).thenReturn(run(MigrationRunStatus.COMPLETED_WITH_ERRORS, 1, 1, 0));

		assertThat(service.execute(DEFINITION_ID, null).status())
				.isEqualTo(MigrationRunStatus.COMPLETED_WITH_ERRORS);
	}

	@Test
	void failedRunIsReturnedAsIs() {
		stubCreatePrepare();
		when(migrationBatchWorker.processNextBatch(RUN_ID))
				.thenReturn(Optional.of(batch("FAILED")))
				.thenReturn(Optional.empty());
		when(migrationRunService.getById(RUN_ID)).thenReturn(run(MigrationRunStatus.FAILED, 1, 0, 1));

		assertThat(service.execute(DEFINITION_ID, null).status()).isEqualTo(MigrationRunStatus.FAILED);
	}

	@Test
	void definitionNotActiveStopsBeforePrepare() {
		when(migrationRunService.create(eq(DEFINITION_ID), any()))
				.thenThrow(new DefinitionNotActiveException(DEFINITION_ID, MigrationConfigStatus.DRAFT));

		assertThatThrownBy(() -> service.execute(DEFINITION_ID, null))
				.isInstanceOf(DefinitionNotActiveException.class);
		verify(sourceInspectionService, never()).prepare(any());
		verify(migrationBatchWorker, never()).processNextBatch(any());
		verify(migrationRunService, never()).getById(any());
	}

	@Test
	void missingDefinitionStopsBeforePrepare() {
		when(migrationRunService.create(eq(DEFINITION_ID), any()))
				.thenThrow(new com.etp.migration.migration.MigrationDefinitionNotFoundException(DEFINITION_ID));

		assertThatThrownBy(() -> service.execute(DEFINITION_ID, null))
				.isInstanceOf(MigrationDefinitionNotFoundException.class);
		verify(sourceInspectionService, never()).prepare(any());
	}

	@Test
	void prepareFailureDoesNotProcessBatches() {
		when(migrationRunService.create(eq(DEFINITION_ID), any())).thenReturn(run(MigrationRunStatus.QUEUED));
		when(sourceInspectionService.prepare(RUN_ID)).thenThrow(new InvalidStateTransitionException("not queued"));

		assertThatThrownBy(() -> service.execute(DEFINITION_ID, null))
				.isInstanceOf(InvalidStateTransitionException.class);
		verify(migrationBatchWorker, never()).processNextBatch(any());
		verify(migrationRunService, never()).getById(any());
		verify(runExecutionLifecycle, never()).beginExecution(any());
		verify(runExecutionLifecycle, never()).finalizeIfIdle(any());
	}

	@Test
	void processNextBatchFailureFinalizesWithoutReturningTheRun() {
		stubCreatePrepare();
		when(migrationBatchWorker.processNextBatch(RUN_ID)).thenThrow(new RuntimeException("batch boom"));

		assertThatThrownBy(() -> service.execute(DEFINITION_ID, null))
				.isInstanceOf(RuntimeException.class)
				.hasMessage("batch boom");
		verify(runExecutionLifecycle).beginExecution(RUN_ID);
		verify(runExecutionLifecycle).finalizeIfIdle(RUN_ID);
		verify(migrationRunService, never()).getById(any());
	}

	@Test
	void shutdownStopsClaimingAfterTheCurrentBatchAndReturnsTheResumableRun() {
		stubCreatePrepare();
		when(migrationBatchWorker.processNextBatch(RUN_ID)).thenAnswer(invocation -> {
			shutdownSignal.onContextClosed(null);
			return Optional.of(batch("COMPLETED"));
		});
		when(migrationRunService.getById(RUN_ID)).thenReturn(run(MigrationRunStatus.RUNNING, 2, 1, 0));

		MigrationRunResponse response = service.execute(DEFINITION_ID, null);

		assertThat(response.status()).isEqualTo(MigrationRunStatus.RUNNING);
		assertThat(response.completedBatches()).isEqualTo(1);
		verify(migrationBatchWorker, times(1)).processNextBatch(RUN_ID);
		verify(migrationRunService).getById(RUN_ID);
	}

	@Test
	void shutdownBeforeTheFirstClaimProcessesNoBatch() {
		stubCreatePrepare();
		shutdownSignal.onContextClosed(null);
		when(migrationRunService.getById(RUN_ID)).thenReturn(run(MigrationRunStatus.RUNNING, 2, 0, 0));

		assertThat(service.execute(DEFINITION_ID, null).status()).isEqualTo(MigrationRunStatus.RUNNING);
		verify(runExecutionLifecycle).beginExecution(RUN_ID);
		verify(runExecutionLifecycle).finalizeIfIdle(RUN_ID);
		verify(migrationBatchWorker, never()).processNextBatch(any());
	}

	private void stubCreatePrepare() {
		when(migrationRunService.create(eq(DEFINITION_ID), any())).thenReturn(run(MigrationRunStatus.QUEUED));
		when(sourceInspectionService.prepare(RUN_ID)).thenReturn(prepare(2));
	}

	private void verifyOrder(int successfulBatches) {
		InOrder order = inOrder(
				migrationRunService, sourceInspectionService, runExecutionLifecycle, migrationBatchWorker);
		order.verify(migrationRunService).create(eq(DEFINITION_ID), any());
		order.verify(sourceInspectionService).prepare(RUN_ID);
		order.verify(runExecutionLifecycle).beginExecution(RUN_ID);
		order.verify(migrationBatchWorker, times(successfulBatches + 1)).processNextBatch(RUN_ID);
		order.verify(runExecutionLifecycle).finalizeIfIdle(RUN_ID);
		order.verify(migrationRunService).getById(RUN_ID);
	}

	private static MigrationRunResponse run(MigrationRunStatus status) {
		return run(status, 0, 0, 0);
	}

	private static MigrationRunResponse run(
			MigrationRunStatus status, int totalBatches, int completedBatches, int failedBatches) {
		Instant now = Instant.parse("2026-09-21T00:00:00Z");
		return new MigrationRunResponse(
				RUN_ID, DEFINITION_ID, 1L, null, status, now, now,
				0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
				totalBatches, completedBatches, failedBatches,
				"system", now, now);
	}

	private static PrepareMigrationRunResponse prepare(int batchCount) {
		return new PrepareMigrationRunResponse(RUN_ID, "QUEUED", 0L, 10_000, batchCount, null, "SOURCE_INSPECTED");
	}

	private static ProcessBatchResponse batch(String status) {
		return new ProcessBatchResponse(RUN_ID, 1L, 1, status, 1, 1, 0, 0);
	}
}
