package com.etp.migration.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.etp.migration.entity.MigrationPhaseJob;
import com.etp.migration.entity.MigrationRun;
import com.etp.migration.processing.MigrationErrorCodes;
import com.etp.migration.promotion.PromotionException;
import com.etp.migration.promotion.PromotionExecutionService;
import com.etp.migration.promotion.PromotionPreparationService;
import com.etp.migration.reconciliation.ReconciliationService;
import com.etp.migration.repository.MigrationPhaseJobRepository;
import com.etp.migration.repository.MigrationRunRepository;
import com.etp.migration.run.dto.PhaseAcceptanceResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class MigrationPhaseSubmissionServiceTest {

	@Mock
	private ReconciliationService reconciliationService;
	@Mock
	private PromotionPreparationService promotionPreparationService;
	@Mock
	private PromotionExecutionService promotionExecutionService;
	@Mock
	private MigrationPhaseJobRepository jobs;
	@Mock
	private MigrationRunRepository runs;
	@Mock
	private MigrationPhaseCoordinator coordinator;
	@Mock
	private PlatformTransactionManager transactionManager;
	@Mock
	private ExecutionControlService controls;

	private MigrationPhaseSubmissionService service;
	private final UUID runId = UUID.fromString("8899968a-41d3-465e-9444-47541f239310");
	private final UUID promotionId = UUID.fromString("9f3db473-ffc9-4851-b78b-aafe8219b290");

	@BeforeEach
	void setUp() {
		lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		service = new MigrationPhaseSubmissionService(
				reconciliationService,
				promotionPreparationService,
				promotionExecutionService,
				jobs,
				runs,
				coordinator,
				transactionManager,
				controls);
		MigrationRun run = new MigrationRun();
		run.setId(runId);
		lenient().when(runs.findByIdForUpdate(runId)).thenReturn(Optional.of(run));
	}

	@Test
	void promotionExecutionReturnsTheExistingPromotionId() {
		when(jobs.findActiveWithoutScope(any(), any(), any())).thenReturn(List.of());
		when(promotionExecutionService.associatedPromotionId(runId)).thenReturn(promotionId);
		when(jobs.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

		PhaseAcceptanceResponse response = service.submitPromotion(runId);

		assertThat(response.promotionId()).isEqualTo(promotionId);
		assertThat(response.jobId()).isNotEqualTo(response.promotionId());
		assertThat(response.runId()).isEqualTo(runId);
		assertThat(response.phase()).isEqualTo(MigrationPhase.PROMOTION);
		assertThat(response.status()).isEqualTo(PhaseJobStatus.QUEUED);
		assertThat(response.statusUrl()).isEqualTo("/api/v1/migration-runs/" + runId + "/promotion");
		assertThat(response.message()).isEqualTo("Phase accepted.");
		verify(promotionPreparationService, never()).reserve(any(), any());
	}

	@Test
	void promotionPreparationReturnsTheReservedPromotionId() {
		when(jobs.findActiveWithoutScope(any(), any(), any())).thenReturn(List.of());
		when(promotionPreparationService.reserve(runId, "system")).thenReturn(promotionId);
		when(jobs.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

		PhaseAcceptanceResponse response = service.submitPreparation(runId);

		assertThat(response.promotionId()).isEqualTo(promotionId);
		assertThat(response.jobId()).isNotEqualTo(promotionId);
		assertThat(response.runId()).isEqualTo(runId);
		assertThat(response.phase()).isEqualTo(MigrationPhase.PROMOTION_PREPARATION);
		assertThat(response.status()).isEqualTo(PhaseJobStatus.QUEUED);
		assertThat(response.statusUrl()).isEqualTo("/api/v1/migration-runs/" + runId + "/promotion");
	}

	@Test
	void missingPromotionLeavesPromotionIdNull() {
		when(jobs.findActiveWithoutScope(any(), any(), any())).thenReturn(List.of());
		when(promotionExecutionService.associatedPromotionId(runId)).thenReturn(null);
		when(jobs.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

		PhaseAcceptanceResponse response = service.submitPromotion(runId);

		assertThat(response.promotionId()).isNull();
		assertThat(response.jobId()).isNotNull();
	}

	@Test
	void duplicatePreparationReturnsTheSamePromotion() {
		AtomicReference<MigrationPhaseJob> stored = new AtomicReference<>();
		when(jobs.findActiveWithoutScope(any(), any(), any())).thenAnswer(invocation ->
				stored.get() == null ? List.of() : List.of(stored.get()));
		when(promotionPreparationService.reserve(runId, "system")).thenReturn(promotionId);
		when(jobs.saveAndFlush(any())).thenAnswer(invocation -> {
			stored.set(invocation.getArgument(0));
			return stored.get();
		});

		PhaseAcceptanceResponse first = service.submitPreparation(runId);
		PhaseAcceptanceResponse second = service.submitPreparation(runId);

		assertThat(second.jobId()).isEqualTo(first.jobId());
		assertThat(second.promotionId()).isEqualTo(promotionId);
		assertThat(second.promotionId()).isNotEqualTo(second.jobId());
		verify(promotionPreparationService, times(1)).reserve(runId, "system");
	}

	@Test
	void concurrentPreparationConflictReturnsTheFirstPromotion() {
		AtomicReference<MigrationPhaseJob> stored = new AtomicReference<>();
		AtomicInteger lookups = new AtomicInteger();
		UUID losingPromotionId = UUID.randomUUID();
		when(jobs.findActiveWithoutScope(any(), any(), any())).thenAnswer(invocation -> {
			if (lookups.incrementAndGet() < 3) {
				return List.of();
			}
			return List.of(stored.get());
		});
		when(promotionPreparationService.reserve(runId, "system"))
				.thenReturn(promotionId)
				.thenReturn(losingPromotionId);
		when(jobs.saveAndFlush(any())).thenAnswer(invocation -> {
			MigrationPhaseJob job = invocation.getArgument(0);
			if (job.getPromotionId().equals(losingPromotionId)) {
				throw new DataIntegrityViolationException("uk_migration_promotion_open");
			}
			stored.set(job);
			return job;
		});

		PhaseAcceptanceResponse first = service.submitPreparation(runId);
		PhaseAcceptanceResponse second = service.submitPreparation(runId);

		assertThat(first.promotionId()).isEqualTo(promotionId);
		assertThat(second.promotionId()).isEqualTo(promotionId);
		assertThat(second.jobId()).isEqualTo(first.jobId());
		assertThat(second.promotionId()).isNotEqualTo(second.jobId());
		assertThat(second.promotionId()).isNotEqualTo(losingPromotionId);
	}

	@Test
	void queuedJobWithoutPromotionIdIsAssociatedOnRetry() {
		MigrationPhaseJob queued = new MigrationPhaseJob();
		queued.setId(UUID.randomUUID());
		queued.setMigrationRunId(runId);
		queued.setPhase(MigrationPhase.PROMOTION_PREPARATION);
		queued.setStatus(PhaseJobStatus.QUEUED);
		when(jobs.findActiveWithoutScope(any(), any(), any())).thenReturn(List.of(queued));
		when(promotionPreparationService.reserve(runId, "system")).thenReturn(promotionId);
		when(jobs.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

		PhaseAcceptanceResponse response = service.submitPreparation(runId);

		assertThat(response.jobId()).isEqualTo(queued.getId());
		assertThat(response.promotionId()).isEqualTo(promotionId);
		assertThat(response.promotionId()).isNotEqualTo(response.jobId());
		assertThat(queued.getPromotionId()).isEqualTo(promotionId);
	}

	@Test
	void failedEligibilityDoesNotReturnAPromotionId() {
		doThrow(new PromotionException(
				409,
				MigrationErrorCodes.PROMOTION_RUN_NOT_COMPLETE,
				"The run is not complete"))
				.when(promotionPreparationService).assertEligible(runId);

		assertThatThrownBy(() -> service.submitPreparation(runId))
				.isInstanceOf(PromotionException.class);
		verify(promotionPreparationService, never()).reserve(any(), any());
		verify(jobs, never()).saveAndFlush(any());
	}
}
