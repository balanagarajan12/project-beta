package com.etp.migration.run;

import com.etp.migration.config.ShutdownSignal;
import com.etp.migration.entity.MigrationPhaseJob;
import com.etp.migration.connector.target.TargetFailureDiagnostics;
import com.etp.migration.processing.MigrationErrorCodes;
import com.etp.migration.processing.MigrationErrorDetails;
import com.etp.migration.processing.MigrationLogContext;
import com.etp.migration.processing.OperationErrorRecorder;
import com.etp.migration.promotion.PromotionException;
import com.etp.migration.promotion.PromotionExecutionService;
import com.etp.migration.promotion.PromotionPausedException;
import com.etp.migration.promotion.PromotionPreparationService;
import com.etp.migration.reconciliation.ReconciliationService;
import com.etp.migration.repository.MigrationPhaseJobRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs one accepted phase job on a background thread. The HTTP thread does not call this.
 */
@Component
public class MigrationPhaseDriver {

	private static final Logger log = LoggerFactory.getLogger(MigrationPhaseDriver.class);

	private final MigrationPhaseClaim claim;
	private final MigrationPhaseJobRepository jobs;
	private final ReconciliationService reconciliationService;
	private final PromotionPreparationService promotionPreparationService;
	private final PromotionExecutionService promotionExecutionService;
	private final MigrationExecutionProgress progress;
	private final ShutdownSignal shutdownSignal;
	private final ExecutionControlService controls;
	private final OperationErrorRecorder operationErrors;

	public MigrationPhaseDriver(
			MigrationPhaseClaim claim,
			MigrationPhaseJobRepository jobs,
			ReconciliationService reconciliationService,
			PromotionPreparationService promotionPreparationService,
			PromotionExecutionService promotionExecutionService,
			MigrationExecutionProgress progress,
			ShutdownSignal shutdownSignal,
			ExecutionControlService controls,
			OperationErrorRecorder operationErrors) {
		this.claim = claim;
		this.jobs = jobs;
		this.reconciliationService = reconciliationService;
		this.promotionPreparationService = promotionPreparationService;
		this.promotionExecutionService = promotionExecutionService;
		this.progress = progress;
		this.shutdownSignal = shutdownSignal;
		this.controls = controls;
		this.operationErrors = operationErrors;
	}

	public void drive(UUID jobId) {
		if (shutdownSignal.isShuttingDown()) {
			log.info("Phase claim skipped because the application is shutting down jobId={}", jobId);
			return;
		}
		if (!claim.tryClaim(jobId)) {
			log.info("Phase claim skipped jobId={}", jobId);
			return;
		}
		MigrationPhaseJob job = jobs.findById(jobId).orElse(null);
		if (job == null) {
			claim.release(jobId);
			return;
		}
		try (MigrationLogContext ignored = MigrationLogContext.forRun(job.getMigrationRunId())) {
			log.info("Phase claimed jobId={} runId={} phase={}", jobId, job.getMigrationRunId(), job.getPhase());
			progress.mark(jobId);
			try {
				Outcome outcome = execute(job);
				progress.mark(jobId);
				// A reconciliation FAILED result is an outcome, not a phase failure. It completes the job
				// and stays on migration_reconciliation. Only a thrown exception records migration_error.
				if (claim.complete(jobId, outcome.value(), outcome.promotionId())) {
					log.info("Phase completed jobId={} runId={} phase={} outcome={}",
							jobId, job.getMigrationRunId(), job.getPhase(), outcome.value());
				} else {
					log.warn("Phase completion skipped because this worker no longer owns the job jobId={}", jobId);
				}
			} catch (PromotionPausedException ex) {
				log.info("Phase paused at a batch boundary jobId={} runId={} phase={}",
						jobId, job.getMigrationRunId(), job.getPhase());
			} catch (PhaseOwnershipLostException ex) {
				log.warn("Phase stopped after losing ownership jobId={} runId={} phase={}",
						jobId, job.getMigrationRunId(), job.getPhase());
			} catch (RuntimeException ex) {
				String code = ex instanceof PromotionException promotion
						? promotion.errorCode()
						: MigrationErrorCodes.PREPARATION_FAILED;
				String message = limit(MigrationErrorDetails.diagnostic(ex));
				if (attachThrowable(ex)) {
					log.warn("Phase failed jobId={} runId={} phase={} errorCode={} cause={}",
							jobId, job.getMigrationRunId(), job.getPhase(), code,
							TargetFailureDiagnostics.describe(ex), ex);
				} else {
					log.warn("Phase failed jobId={} runId={} phase={} errorCode={} cause={}",
							jobId, job.getMigrationRunId(), job.getPhase(), code,
							TargetFailureDiagnostics.describe(ex));
				}
				if (claim.fail(jobId, code, message)) {
					operationErrors.recordPhase(
							job.getMigrationRunId(),
							jobId,
							job.getPhase(),
							code,
							message,
							ex,
							!(ex instanceof PromotionException));
				} else {
					log.warn("Phase failure was not recorded because this worker no longer owns the job jobId={}", jobId);
				}
			} finally {
				claim.release(jobId);
			}
		}
	}

	private Outcome execute(MigrationPhaseJob job) {
		UUID runId = job.getMigrationRunId();
		return switch (job.getPhase()) {
			case RECONCILIATION -> {
				var response = reconciliationService.reconcile(runId, job.getScope(), () -> progress.mark(job.getId()));
				String outcome = response.status() == null ? null : response.status().name();
				yield new Outcome(outcome, null);
			}
			case PROMOTION_PREPARATION -> {
				var response = promotionPreparationService.prepare(
						runId, null, () -> progress.mark(job.getId()), job.getRequestedBy());
				yield new Outcome(response.status().name(), response.promotionId());
			}
			case PROMOTION -> {
				controls.markPromotionRunning(runId);
				var response = promotionExecutionService.promote(
						runId,
						() -> progress.mark(job.getId()),
						() -> claim.holdsValidLease(job.getId()));
				yield new Outcome(response.status().name(), response.promotionId());
			}
		};
	}

	private static boolean attachThrowable(Throwable failure) {
		String message = failure.getMessage();
		return message == null || message.isBlank() || !MigrationErrorDetails.sensitive(message);
	}

	private static String limit(String message) {
		if (message == null || message.isBlank()) {
			return "Phase job failed";
		}
		return message.length() <= 2000 ? message : message.substring(0, 2000);
	}

	private record Outcome(String value, UUID promotionId) {
	}
}
