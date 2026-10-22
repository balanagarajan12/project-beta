package com.etp.migration.run;

import com.etp.migration.config.AuditActor;
import com.etp.migration.entity.MigrationPhaseJob;
import com.etp.migration.processing.MigrationErrorDetails;
import com.etp.migration.promotion.PromotionExecutionService;
import com.etp.migration.promotion.PromotionPreparationService;
import com.etp.migration.reconciliation.ReconciliationService;
import com.etp.migration.repository.MigrationPhaseJobRepository;
import com.etp.migration.repository.MigrationRunRepository;
import com.etp.migration.run.dto.PhaseAcceptanceResponse;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Validates a phase, stores the job, and only then schedules it.
 * A second request for a queued or running job returns that job instead of starting another.
 */
@Service
public class MigrationPhaseSubmissionService {

	private static final Logger log = LoggerFactory.getLogger(MigrationPhaseSubmissionService.class);

	private final ReconciliationService reconciliationService;
	private final PromotionPreparationService promotionPreparationService;
	private final PromotionExecutionService promotionExecutionService;
	private final MigrationPhaseJobRepository jobs;
	private final MigrationRunRepository runs;
	private final MigrationPhaseCoordinator coordinator;
	private final TransactionTemplate transactionTemplate;
	private final ExecutionControlService controls;

	public MigrationPhaseSubmissionService(
			ReconciliationService reconciliationService,
			PromotionPreparationService promotionPreparationService,
			PromotionExecutionService promotionExecutionService,
			MigrationPhaseJobRepository jobs,
			MigrationRunRepository runs,
			MigrationPhaseCoordinator coordinator,
			PlatformTransactionManager transactionManager,
			ExecutionControlService controls) {
		this.reconciliationService = reconciliationService;
		this.promotionPreparationService = promotionPreparationService;
		this.promotionExecutionService = promotionExecutionService;
		this.jobs = jobs;
		this.runs = runs;
		this.coordinator = coordinator;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.controls = controls;
	}

	public PhaseAcceptanceResponse submitReconciliation(UUID runId, String verificationScope) {
		reconciliationService.assertSubmittable(runId, verificationScope);
		String scope = businessTarget(verificationScope) ? "BUSINESS_TARGET" : null;
		return accept(runId, MigrationPhase.RECONCILIATION, scope, null);
	}

	public PhaseAcceptanceResponse submitPreparation(UUID runId) {
		String actor = AuditActor.current();
		promotionPreparationService.assertEligible(runId);
		return accept(runId, MigrationPhase.PROMOTION_PREPARATION, null, actor);
	}

	public PhaseAcceptanceResponse submitPromotion(UUID runId) {
		controls.requirePromotionRunnable(runId);
		promotionExecutionService.assertPromotable(runId);
		return accept(runId, MigrationPhase.PROMOTION, null, null);
	}

	@Transactional(readOnly = true)
	public PhaseAcceptanceResponse get(UUID runId, UUID jobId) {
		MigrationPhaseJob job = jobs.findById(jobId)
				.filter(candidate -> runId.equals(candidate.getMigrationRunId()))
				.orElseThrow(() -> new PhaseJobNotFoundException(jobId));
		return toResponse(job, false);
	}

	private PhaseAcceptanceResponse accept(UUID runId, MigrationPhase phase, String scope, String requestedBy) {
		PhaseAcceptanceResponse accepted;
		try {
			accepted = transactionTemplate.execute(status -> insert(runId, phase, scope, requestedBy));
		} catch (DataIntegrityViolationException ex) {
			accepted = active(runId, phase, scope);
			if (accepted == null) {
				throw ex;
			}
		}
		if (accepted != null && accepted.status() == PhaseJobStatus.QUEUED) {
			coordinator.submit(accepted.jobId());
			log.info("Phase accepted jobId={} runId={} phase={}", accepted.jobId(), runId, phase);
		}
		return accepted;
	}

	private PhaseAcceptanceResponse insert(UUID runId, MigrationPhase phase, String scope, String requestedBy) {
		if (phase == MigrationPhase.PROMOTION_PREPARATION || phase == MigrationPhase.PROMOTION) {
			runs.findByIdForUpdate(runId).orElseThrow(() -> new MigrationRunNotFoundException(runId));
		}
		MigrationPhaseJob existing = activeJob(runId, phase, scope);
		if (existing != null) {
			attachPromotion(existing, requestedBy);
			return toResponse(existing, false);
		}
		UUID promotionId = switch (phase) {
			case PROMOTION_PREPARATION -> promotionPreparationService.reserve(runId, requestedBy);
			case PROMOTION -> promotionExecutionService.associatedPromotionId(runId);
			case RECONCILIATION -> null;
		};
		MigrationPhaseJob job = new MigrationPhaseJob();
		job.setId(UUID.randomUUID());
		job.setMigrationRunId(runId);
		job.setPhase(phase);
		job.setStatus(PhaseJobStatus.QUEUED);
		job.setScope(scope);
		job.setPromotionId(promotionId);
		job.setRequestedBy(requestedBy);
		job.setAttemptCount(0);
		jobs.saveAndFlush(job);
		return toResponse(job, true);
	}

	private PhaseAcceptanceResponse active(UUID runId, MigrationPhase phase, String scope) {
		MigrationPhaseJob job = activeJob(runId, phase, scope);
		if (job == null) {
			return null;
		}
		attachPromotion(job, job.getRequestedBy());
		return toResponse(job, false);
	}

	private MigrationPhaseJob activeJob(UUID runId, MigrationPhase phase, String scope) {
		List<MigrationPhaseJob> active = scope == null
				? jobs.findActiveWithoutScope(
						runId,
						phase,
						EnumSet.of(PhaseJobStatus.QUEUED, PhaseJobStatus.RUNNING))
				: jobs.findActiveWithScope(
						runId,
						phase,
						EnumSet.of(PhaseJobStatus.QUEUED, PhaseJobStatus.RUNNING),
						scope);
		return active.isEmpty() ? null : active.getFirst();
	}

	private void attachPromotion(MigrationPhaseJob job, String requestedBy) {
		if (job.getPromotionId() != null || job.getPhase() == MigrationPhase.RECONCILIATION) {
			return;
		}
		UUID promotionId = switch (job.getPhase()) {
			case PROMOTION_PREPARATION -> promotionPreparationService.reserve(job.getMigrationRunId(), requestedBy);
			case PROMOTION -> promotionExecutionService.associatedPromotionId(job.getMigrationRunId());
			case RECONCILIATION -> null;
		};
		if (promotionId == null) {
			return;
		}
		job.setPromotionId(promotionId);
		jobs.saveAndFlush(job);
	}

	private static boolean businessTarget(String verificationScope) {
		return verificationScope != null && "BUSINESS_TARGET".equalsIgnoreCase(verificationScope.trim());
	}

	private static PhaseAcceptanceResponse toResponse(MigrationPhaseJob job, boolean created) {
		String url = job.getPhase() == MigrationPhase.RECONCILIATION
				? "/api/v1/migration-runs/" + job.getMigrationRunId() + "/reconciliation"
				: "/api/v1/migration-runs/" + job.getMigrationRunId() + "/promotion";
		String message = created || job.getStatus() == PhaseJobStatus.QUEUED
				? "Phase accepted."
				: "Phase job already exists.";
		return new PhaseAcceptanceResponse(
				job.getId(),
				job.getMigrationRunId(),
				job.getPromotionId(),
				job.getPhase(),
				job.getStatus(),
				job.getOutcome(),
				message,
				job.getErrorCode(),
				MigrationErrorDetails.publicMessage(job.getErrorMessage()),
				url);
	}
}
