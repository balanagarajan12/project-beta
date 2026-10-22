package com.etp.migration.run;

import com.etp.migration.entity.MigrationPhaseJob;
import com.etp.migration.entity.MigrationPromotion;
import com.etp.migration.entity.MigrationRun;
import com.etp.migration.entity.PromotionStatus;
import com.etp.migration.processing.MigrationErrorCodes;
import com.etp.migration.promotion.PromotionException;
import com.etp.migration.promotion.PromotionNotFoundException;
import com.etp.migration.repository.MigrationPhaseJobRepository;
import com.etp.migration.repository.MigrationPromotionRepository;
import com.etp.migration.repository.MigrationRunRepository;
import com.etp.migration.run.dto.ExecutionControlResponse;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists pause and resume independently of lifecycle status.
 * A pause requested while a worker holds a lease stays {@code PAUSE_REQUESTED} until that worker
 * reaches a batch boundary and stores {@code PAUSED}. Recovery does not claim either state.
 */
@Service
public class ExecutionControlService {

	private static final EnumSet<PromotionStatus> TERMINAL_PROMOTION = EnumSet.of(
			PromotionStatus.PROMOTED,
			PromotionStatus.PARTIALLY_PROMOTED,
			PromotionStatus.PROMOTION_FAILED);
	private static final EnumSet<PhaseJobStatus> ACTIVE_JOB = EnumSet.of(PhaseJobStatus.QUEUED, PhaseJobStatus.RUNNING);

	private final MigrationRunRepository runs;
	private final MigrationPromotionRepository promotions;
	private final MigrationPhaseJobRepository jobs;

	public ExecutionControlService(
			MigrationRunRepository runs,
			MigrationPromotionRepository promotions,
			MigrationPhaseJobRepository jobs) {
		this.runs = runs;
		this.promotions = promotions;
		this.jobs = jobs;
	}

	@Transactional
	public ExecutionControlResponse pauseRun(UUID runId) {
		MigrationRun run = lockedRun(runId);
		if (MigrationRunTransitions.isTerminal(run.getStatus())) {
			throw new InvalidStateTransitionException("A finished migration run cannot be paused");
		}
		ExecutionControlState current = state(run.getControlState());
		if (current == ExecutionControlState.PAUSED || current == ExecutionControlState.PAUSE_REQUESTED) {
			return runResponse(run, "PAUSE", null);
		}
		boolean workerActive = leaseOpen(run.getExecutionLeaseUntil());
		apply(run, workerActive ? ExecutionControlState.PAUSE_REQUESTED : ExecutionControlState.PAUSED, "PAUSE");
		return runResponse(run, "PAUSE", null);
	}

	@Transactional
	public ExecutionControlResponse resumeRun(UUID runId) {
		MigrationRun run = lockedRun(runId);
		if (MigrationRunTransitions.isTerminal(run.getStatus())) {
			throw new InvalidStateTransitionException("A finished migration run cannot be resumed");
		}
		ExecutionControlState current = state(run.getControlState());
		if (current == ExecutionControlState.RUNNING || current == ExecutionControlState.RESUMING) {
			return runResponse(run, "RESUME", null);
		}
		apply(run, ExecutionControlState.RESUMING, "RESUME");
		return runResponse(run, "RESUME", runId);
	}

	@Transactional
	public ExecutionControlResponse pausePromotion(UUID runId) {
		lockedRun(runId);
		MigrationPromotion promotion = latest(runId);
		if (TERMINAL_PROMOTION.contains(promotion.getStatus())) {
			throw pausedConflict("A finished promotion cannot be paused");
		}
		ExecutionControlState current = state(promotion.getControlState());
		if (current == ExecutionControlState.PAUSED || current == ExecutionControlState.PAUSE_REQUESTED) {
			return promotionResponse(runId, promotion, "PAUSE", null);
		}
		boolean workerActive = activePromotionJob(runId).stream().anyMatch(job -> leaseOpen(job.getLeaseUntil()));
		apply(promotion, workerActive ? ExecutionControlState.PAUSE_REQUESTED : ExecutionControlState.PAUSED, "PAUSE");
		return promotionResponse(runId, promotion, "PAUSE", null);
	}

	@Transactional
	public ExecutionControlResponse resumePromotion(UUID runId) {
		lockedRun(runId);
		MigrationPromotion promotion = latest(runId);
		if (TERMINAL_PROMOTION.contains(promotion.getStatus())) {
			throw pausedConflict("A finished promotion cannot be resumed");
		}
		ExecutionControlState current = state(promotion.getControlState());
		if (current == ExecutionControlState.RUNNING || current == ExecutionControlState.RESUMING) {
			return promotionResponse(runId, promotion, "RESUME", null);
		}
		apply(promotion, ExecutionControlState.RESUMING, "RESUME");
		UUID jobId = null;
		if (current == ExecutionControlState.PAUSED) {
			jobId = activePromotionJob(runId).stream()
					.filter(job -> !leaseOpen(job.getLeaseUntil()))
					.map(MigrationPhaseJob::getId)
					.findFirst()
					.orElse(null);
		}
		return promotionResponse(runId, promotion, "RESUME", jobId);
	}

	@Transactional(readOnly = true)
	public void requireRunRunnable(UUID runId) {
		MigrationRun run = runs.findById(runId).orElseThrow(() -> new MigrationRunNotFoundException(runId));
		if (halted(state(run.getControlState()))) {
			throw new InvalidStateTransitionException("Migration run is paused. Resume it before claiming more batches.");
		}
	}

	@Transactional(readOnly = true)
	public void requirePromotionRunnable(UUID runId) {
		List<MigrationPromotion> existing = promotions.findByMigrationRun_IdOrderByCreatedAtDesc(runId);
		if (existing.isEmpty()) {
			return;
		}
		if (halted(state(existing.getFirst().getControlState()))) {
			throw pausedConflict("Promotion is paused. Resume it before claiming more batches.");
		}
	}

	@Transactional(readOnly = true)
	public boolean shouldPauseRun(UUID runId) {
		return runs.findById(runId)
				.map(run -> halted(state(run.getControlState())))
				.orElse(false);
	}

	@Transactional
	public void markRunPaused(UUID runId) {
		MigrationRun run = lockedRun(runId);
		if (state(run.getControlState()) != ExecutionControlState.PAUSED) {
			apply(run, ExecutionControlState.PAUSED, "PAUSE");
		}
	}

	@Transactional
	public void markRunRunning(UUID runId) {
		MigrationRun run = lockedRun(runId);
		if (state(run.getControlState()) == ExecutionControlState.RESUMING) {
			apply(run, ExecutionControlState.RUNNING, "RESUME");
		}
	}

	@Transactional(readOnly = true)
	public boolean shouldPausePromotion(UUID promotionId) {
		return promotions.findById(promotionId)
				.map(promotion -> halted(state(promotion.getControlState())))
				.orElse(false);
	}

	@Transactional
	public void markPromotionPaused(UUID promotionId) {
		MigrationPromotion promotion = promotions.findById(promotionId)
				.orElseThrow(() -> new PromotionException(
						HttpStatus.NOT_FOUND.value(),
						MigrationErrorCodes.PROMOTION_NOT_EXECUTED,
						"Promotion not found"));
		if (state(promotion.getControlState()) != ExecutionControlState.PAUSED) {
			apply(promotion, ExecutionControlState.PAUSED, "PAUSE");
		}
	}

	@Transactional
	public void markPromotionRunning(UUID runId) {
		List<MigrationPromotion> existing = promotions.findByMigrationRun_IdOrderByCreatedAtDesc(runId);
		if (existing.isEmpty()) {
			return;
		}
		MigrationPromotion promotion = existing.getFirst();
		if (state(promotion.getControlState()) == ExecutionControlState.RESUMING) {
			apply(promotion, ExecutionControlState.RUNNING, "RESUME");
		}
	}

	private MigrationRun lockedRun(UUID runId) {
		return runs.findByIdForUpdate(runId).orElseThrow(() -> new MigrationRunNotFoundException(runId));
	}

	private MigrationPromotion latest(UUID runId) {
		List<MigrationPromotion> existing = promotions.findByMigrationRun_IdOrderByCreatedAtDesc(runId);
		if (existing.isEmpty()) {
			throw new PromotionNotFoundException(runId);
		}
		return existing.getFirst();
	}

	private List<MigrationPhaseJob> activePromotionJob(UUID runId) {
		return jobs.findActiveWithoutScope(runId, MigrationPhase.PROMOTION, ACTIVE_JOB);
	}

	private static boolean leaseOpen(Instant leaseUntil) {
		return leaseUntil != null && leaseUntil.isAfter(Instant.now());
	}

	private static boolean halted(ExecutionControlState state) {
		return state == ExecutionControlState.PAUSE_REQUESTED || state == ExecutionControlState.PAUSED;
	}

	private static ExecutionControlState state(ExecutionControlState value) {
		return value == null ? ExecutionControlState.RUNNING : value;
	}

	private static void apply(MigrationRun run, ExecutionControlState state, String action) {
		run.setControlState(state);
		run.setLastControlAction(action);
		run.setControlUpdatedAt(Instant.now());
	}

	private static void apply(MigrationPromotion promotion, ExecutionControlState state, String action) {
		promotion.setControlState(state);
		promotion.setLastControlAction(action);
		promotion.setControlUpdatedAt(Instant.now());
	}

	private static ExecutionControlResponse runResponse(MigrationRun run, String action, UUID resumeRunId) {
		return new ExecutionControlResponse(
				run.getId(),
				null,
				state(run.getControlState()),
				action,
				run.getControlUpdatedAt(),
				resumeRunId);
	}

	private static ExecutionControlResponse promotionResponse(
			UUID runId,
			MigrationPromotion promotion,
			String action,
			UUID resumeJobId) {
		return new ExecutionControlResponse(
				runId,
				promotion.getId(),
				state(promotion.getControlState()),
				action,
				promotion.getControlUpdatedAt(),
				resumeJobId);
	}

	private static PromotionException pausedConflict(String message) {
		return new PromotionException(HttpStatus.CONFLICT.value(), MigrationErrorCodes.PROMOTION_IN_PROGRESS, message);
	}
}
