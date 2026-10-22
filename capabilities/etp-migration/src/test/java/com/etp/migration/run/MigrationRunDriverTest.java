package com.etp.migration.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.etp.migration.config.ShutdownSignal;
import com.etp.migration.processing.MigrationBatchWorker;
import com.etp.migration.processing.PrepareMigrationRunResponse;
import com.etp.migration.processing.ProcessBatchResponse;
import com.etp.migration.processing.SourceInspectionService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MigrationRunDriverTest {

	private static final UUID RUN_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

	@Mock
	private SourceInspectionService sourceInspectionService;
	@Mock
	private MigrationBatchWorker migrationBatchWorker;
	@Mock
	private RunExecutionLifecycle runExecutionLifecycle;
	@Mock
	private MigrationExecutionClaim claim;

	@Mock
	private ExecutionControlService controls;

	private final ShutdownSignal shutdownSignal = new ShutdownSignal();
	private MigrationRunDriver driver;

	@BeforeEach
	void setUp() {
		driver = new MigrationRunDriver(
				sourceInspectionService,
				migrationBatchWorker,
				runExecutionLifecycle,
				claim,
				new MigrationExecutionProgress(),
				shutdownSignal,
				controls);
	}

	@Test
	void claimedRunPreparesThenDrainsBatchesAndFinalizes() {
		when(claim.tryClaim(RUN_ID)).thenReturn(true);
		when(claim.keep(RUN_ID)).thenReturn(MigrationExecutionClaim.Ownership.ACTIVE);
		when(sourceInspectionService.prepare(RUN_ID)).thenReturn(prepare(2));
		when(migrationBatchWorker.processNextBatch(RUN_ID))
				.thenReturn(Optional.of(batch()))
				.thenReturn(Optional.of(batch()))
				.thenReturn(Optional.empty());

		driver.drive(RUN_ID);

		InOrder order = inOrder(sourceInspectionService, runExecutionLifecycle, migrationBatchWorker);
		order.verify(runExecutionLifecycle).beginExecution(RUN_ID);
		order.verify(sourceInspectionService).prepare(RUN_ID);
		order.verify(migrationBatchWorker, times(3)).processNextBatch(RUN_ID);
		order.verify(runExecutionLifecycle).finalizeIfIdle(RUN_ID);
		verify(claim).release(RUN_ID);
		verify(migrationBatchWorker, never()).processNextBatchOrConflict(any());
	}

	@Test
	void skippedClaimDoesNotPrepare() {
		when(claim.tryClaim(RUN_ID)).thenReturn(false);

		driver.drive(RUN_ID);

		verify(sourceInspectionService, never()).prepare(any());
		verify(migrationBatchWorker, never()).processNextBatch(any());
		verify(claim, never()).release(any());
	}

	@Test
	void preparationFailureDoesNotProcessBatchesOrFinalize() {
		when(claim.tryClaim(RUN_ID)).thenReturn(true);
		when(sourceInspectionService.prepare(RUN_ID)).thenThrow(new InvalidStateTransitionException("not queued"));

		driver.drive(RUN_ID);

		verify(migrationBatchWorker, never()).processNextBatch(any());
		verify(runExecutionLifecycle).beginExecution(RUN_ID);
		verify(runExecutionLifecycle, never()).finalizeIfIdle(any());
		verify(claim).release(RUN_ID);
	}

	@Test
	void batchFailureFinalizesWhenThisWorkerStillOwnsTheRun() {
		when(claim.tryClaim(RUN_ID)).thenReturn(true);
		when(claim.keep(RUN_ID)).thenReturn(MigrationExecutionClaim.Ownership.ACTIVE);
		when(sourceInspectionService.prepare(RUN_ID)).thenReturn(prepare(1));
		when(migrationBatchWorker.processNextBatch(RUN_ID)).thenThrow(new RuntimeException("batch boom"));

		driver.drive(RUN_ID);

		verify(runExecutionLifecycle).beginExecution(RUN_ID);
		verify(runExecutionLifecycle).finalizeIfIdle(RUN_ID);
		verify(claim).release(RUN_ID);
	}

	@Test
	void lostLeaseAfterPreparationDoesNotFinalizeOrReleaseAnotherWorker() {
		when(claim.tryClaim(RUN_ID)).thenReturn(true);
		when(claim.keep(RUN_ID)).thenReturn(MigrationExecutionClaim.Ownership.LOST);
		when(sourceInspectionService.prepare(RUN_ID)).thenReturn(prepare(1));

		driver.drive(RUN_ID);

		verify(runExecutionLifecycle).beginExecution(RUN_ID);
		verify(runExecutionLifecycle, never()).finalizeIfIdle(any());
		verify(claim).release(RUN_ID);
	}

	@Test
	void finishedRunAfterTheLastBatchIsNotReportedAsALostLease() {
		when(claim.tryClaim(RUN_ID)).thenReturn(true);
		when(claim.keep(RUN_ID))
				.thenReturn(MigrationExecutionClaim.Ownership.ACTIVE)
				.thenReturn(MigrationExecutionClaim.Ownership.ACTIVE)
				.thenReturn(MigrationExecutionClaim.Ownership.FINISHED);
		when(sourceInspectionService.prepare(RUN_ID)).thenReturn(prepare(1));
		when(migrationBatchWorker.processNextBatch(RUN_ID)).thenReturn(Optional.of(batch()));

		driver.drive(RUN_ID);

		verify(migrationBatchWorker, times(1)).processNextBatch(RUN_ID);
		verify(runExecutionLifecycle).beginExecution(RUN_ID);
		verify(runExecutionLifecycle, never()).finalizeIfIdle(any());
		verify(claim).release(RUN_ID);
	}

	@Test
	void shutdownStopsClaimingAndDoesNotFinalize() {
		when(claim.tryClaim(RUN_ID)).thenReturn(true);
		when(claim.keep(RUN_ID)).thenReturn(MigrationExecutionClaim.Ownership.ACTIVE);
		when(sourceInspectionService.prepare(RUN_ID)).thenReturn(prepare(2));
		when(migrationBatchWorker.processNextBatch(RUN_ID)).thenAnswer(invocation -> {
			shutdownSignal.onContextClosed(null);
			return Optional.of(batch());
		});

		driver.drive(RUN_ID);

		verify(migrationBatchWorker, times(1)).processNextBatch(RUN_ID);
		verify(runExecutionLifecycle, never()).finalizeIfIdle(any());
		verify(claim).release(RUN_ID);
	}

	@Test
	void shutdownBeforeClaimDoesNotTouchTheRun() {
		shutdownSignal.onContextClosed(null);

		driver.drive(RUN_ID);

		verify(claim, never()).tryClaim(any());
		verify(sourceInspectionService, never()).prepare(any());
		assertThat(shutdownSignal.isShuttingDown()).isTrue();
	}

	private static PrepareMigrationRunResponse prepare(int batchCount) {
		return new PrepareMigrationRunResponse(RUN_ID, "QUEUED", 0L, 10_000, batchCount, null, "SOURCE_INSPECTED");
	}

	private static ProcessBatchResponse batch() {
		return new ProcessBatchResponse(RUN_ID, 1L, 1, "COMPLETED", 1, 1, 0, 0);
	}
}
