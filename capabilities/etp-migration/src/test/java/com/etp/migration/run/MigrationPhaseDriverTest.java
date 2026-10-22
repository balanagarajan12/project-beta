package com.etp.migration.run;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.etp.migration.config.ShutdownSignal;
import com.etp.migration.entity.MigrationPhaseJob;
import com.etp.migration.entity.ReconciliationStatus;
import com.etp.migration.processing.OperationErrorRecorder;
import com.etp.migration.promotion.PromotionException;
import com.etp.migration.promotion.PromotionExecutionService;
import com.etp.migration.promotion.PromotionPreparationService;
import com.etp.migration.reconciliation.ReconciliationService;
import com.etp.migration.reconciliation.dto.MigrationReconciliationResponse;
import com.etp.migration.reconciliation.dto.ReconciliationBundleResponse;
import com.etp.migration.repository.MigrationPhaseJobRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class MigrationPhaseDriverTest {

	@Mock
	private MigrationPhaseClaim claim;

	@Mock
	private MigrationPhaseJobRepository jobs;

	@Mock
	private ReconciliationService reconciliationService;

	@Mock
	private PromotionPreparationService promotionPreparationService;

	@Mock
	private PromotionExecutionService promotionExecutionService;

	@Mock
	private ShutdownSignal shutdownSignal;

	@Mock
	private ExecutionControlService controls;

	@Mock
	private OperationErrorRecorder operationErrors;

	private MigrationPhaseDriver driver;

	@BeforeEach
	void setUp() {
		driver = new MigrationPhaseDriver(
				claim,
				jobs,
				reconciliationService,
				promotionPreparationService,
				promotionExecutionService,
				new MigrationExecutionProgress(),
				shutdownSignal,
				controls,
				operationErrors);
	}

	@Test
	void reconciliationOutcomeFailedStillCompletesTheJob() {
		UUID jobId = UUID.randomUUID();
		UUID runId = UUID.randomUUID();
		when(claim.tryClaim(jobId)).thenReturn(true);
		when(jobs.findById(jobId)).thenReturn(Optional.of(job(jobId, runId, MigrationPhase.RECONCILIATION)));
		when(reconciliationService.reconcile(eq(runId), isNull(), any())).thenReturn(
				new MigrationReconciliationResponse(
						runId,
						ReconciliationStatus.FAILED,
						new ReconciliationBundleResponse(null, null, null, null)));
		when(claim.complete(jobId, "FAILED", null)).thenReturn(true);

		driver.drive(jobId);

		verify(claim).complete(jobId, "FAILED", null);
		verify(claim, never()).fail(any(), any(), any());
		verify(operationErrors, never()).recordPhase(any(), any(), any(), any(), any(), any(), anyBoolean());
		verify(claim).release(jobId);
	}

	@Test
	void workerExceptionFailsOnlyTheRunningJob() {
		UUID jobId = UUID.randomUUID();
		UUID runId = UUID.randomUUID();
		when(claim.tryClaim(jobId)).thenReturn(true);
		when(jobs.findById(jobId)).thenReturn(Optional.of(job(jobId, runId, MigrationPhase.PROMOTION)));
		when(promotionExecutionService.promote(eq(runId), any(), any()))
				.thenThrow(new PromotionException(HttpStatus.CONFLICT.value(), "MIGRATION_PROMOTION_NOT_READY", "not ready"));
		when(claim.fail(jobId, "MIGRATION_PROMOTION_NOT_READY", "not ready")).thenReturn(true);

		driver.drive(jobId);

		verify(claim).fail(jobId, "MIGRATION_PROMOTION_NOT_READY", "not ready");
		verify(operationErrors).recordPhase(
				eq(runId),
				eq(jobId),
				eq(MigrationPhase.PROMOTION),
				eq("MIGRATION_PROMOTION_NOT_READY"),
				eq("not ready"),
				any(PromotionException.class),
				eq(false));
		verify(claim, never()).complete(any(), any(), any());
		verify(claim).release(jobId);
	}

	@Test
	void lostOwnershipDoesNotFailOrCompleteTheJob() {
		UUID jobId = UUID.randomUUID();
		UUID runId = UUID.randomUUID();
		when(claim.tryClaim(jobId)).thenReturn(true);
		when(jobs.findById(jobId)).thenReturn(Optional.of(job(jobId, runId, MigrationPhase.PROMOTION)));
		when(promotionExecutionService.promote(eq(runId), any(), any()))
				.thenThrow(new PhaseOwnershipLostException(runId));

		driver.drive(jobId);

		verify(claim, never()).complete(any(), any(), any());
		verify(claim, never()).fail(any(), any(), any());
		verify(operationErrors, never()).recordPhase(any(), any(), any(), any(), any(), any(), anyBoolean());
		verify(claim).release(jobId);
	}

	@Test
	void failureOfAJobOwnedByAnotherWorkerIsNotRecorded() {
		UUID jobId = UUID.randomUUID();
		UUID runId = UUID.randomUUID();
		when(claim.tryClaim(jobId)).thenReturn(true);
		when(jobs.findById(jobId)).thenReturn(Optional.of(job(jobId, runId, MigrationPhase.PROMOTION_PREPARATION)));
		when(promotionPreparationService.prepare(eq(runId), isNull(), any(), isNull()))
				.thenThrow(new IllegalStateException("database timeout"));
		when(claim.fail(eq(jobId), any(), any())).thenReturn(false);

		driver.drive(jobId);

		verify(claim).fail(eq(jobId), any(), any());
		verify(operationErrors, never()).recordPhase(any(), any(), any(), any(), any(), any(), anyBoolean());
		verify(claim, never()).complete(any(), any(), any());
	}

	private static MigrationPhaseJob job(UUID jobId, UUID runId, MigrationPhase phase) {
		MigrationPhaseJob job = new MigrationPhaseJob();
		job.setId(jobId);
		job.setMigrationRunId(runId);
		job.setPhase(phase);
		job.setStatus(PhaseJobStatus.QUEUED);
		return job;
	}
}
