package com.etp.migration.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.etp.migration.entity.MigrationPromotion;
import com.etp.migration.entity.MigrationRun;
import com.etp.migration.entity.MigrationRunStatus;
import com.etp.migration.entity.PromotionStatus;
import com.etp.migration.repository.MigrationPhaseJobRepository;
import com.etp.migration.repository.MigrationPromotionRepository;
import com.etp.migration.repository.MigrationRunRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExecutionControlServiceTest {

	@Mock
	private MigrationRunRepository runs;
	@Mock
	private MigrationPromotionRepository promotions;
	@Mock
	private MigrationPhaseJobRepository jobs;

	private ExecutionControlService service;
	private UUID runId;

	@BeforeEach
	void setUp() {
		service = new ExecutionControlService(runs, promotions, jobs);
		runId = UUID.randomUUID();
	}

	@Test
	void pauseWithoutALeaseBecomesPausedImmediately() {
		MigrationRun run = running();
		when(runs.findByIdForUpdate(runId)).thenReturn(Optional.of(run));

		var response = service.pauseRun(runId);

		assertThat(response.controlState()).isEqualTo(ExecutionControlState.PAUSED);
		assertThat(run.getControlState()).isEqualTo(ExecutionControlState.PAUSED);
	}

	@Test
	void pauseWithAnOpenLeaseWaitsForTheBatchBoundary() {
		MigrationRun run = running();
		run.setExecutionLeaseUntil(Instant.now().plusSeconds(60));
		when(runs.findByIdForUpdate(runId)).thenReturn(Optional.of(run));

		var response = service.pauseRun(runId);

		assertThat(response.controlState()).isEqualTo(ExecutionControlState.PAUSE_REQUESTED);
	}

	@Test
	void finishedRunCannotBePaused() {
		MigrationRun run = running();
		run.setStatus(MigrationRunStatus.COMPLETED);
		when(runs.findByIdForUpdate(runId)).thenReturn(Optional.of(run));

		assertThatThrownBy(() -> service.pauseRun(runId)).isInstanceOf(InvalidStateTransitionException.class);
	}

	@Test
	void resumeOfPausedPromotionReturnsTheIdleJob() {
		MigrationRun run = running();
		MigrationPromotion promotion = new MigrationPromotion();
		promotion.setId(UUID.randomUUID());
		promotion.setStatus(PromotionStatus.PROMOTING);
		promotion.setControlState(ExecutionControlState.PAUSED);
		when(runs.findByIdForUpdate(runId)).thenReturn(Optional.of(run));
		when(promotions.findByMigrationRun_IdOrderByCreatedAtDesc(runId)).thenReturn(List.of(promotion));
		when(jobs.findActiveWithoutScope(runId, MigrationPhase.PROMOTION, java.util.EnumSet.of(PhaseJobStatus.QUEUED, PhaseJobStatus.RUNNING)))
				.thenReturn(List.of());

		var response = service.resumePromotion(runId);

		assertThat(response.controlState()).isEqualTo(ExecutionControlState.RESUMING);
		assertThat(response.resumeJobId()).isNull();
	}

	private MigrationRun running() {
		MigrationRun run = new MigrationRun();
		run.setId(runId);
		run.setStatus(MigrationRunStatus.RUNNING);
		run.setControlState(ExecutionControlState.RUNNING);
		return run;
	}
}
