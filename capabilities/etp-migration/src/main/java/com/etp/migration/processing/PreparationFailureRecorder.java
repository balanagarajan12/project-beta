package com.etp.migration.processing;

import com.etp.migration.connector.source.SftpSourceException;
import com.etp.migration.connector.target.TargetFailureDiagnostics;
import com.etp.migration.connector.target.TargetInfrastructureException;
import com.etp.migration.entity.MigrationBatchStatus;
import com.etp.migration.entity.MigrationError;
import com.etp.migration.entity.MigrationRun;
import com.etp.migration.entity.MigrationRunStatus;
import com.etp.migration.entity.MigrationStage;
import com.etp.migration.repository.MigrationBatchRepository;
import com.etp.migration.repository.MigrationErrorRepository;
import com.etp.migration.repository.MigrationRunRepository;
import com.etp.migration.run.InvalidStateTransitionException;
import com.etp.migration.run.MigrationRunNotFoundException;
import com.etp.migration.run.MigrationRunTransitions;
import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Records a preparation or planning failure and fails an unprepared QUEUED or RUNNING run.
 * A run that was only created, or that already has batches in flight, is left unchanged.
 */
@Component
public class PreparationFailureRecorder {

	private static final Logger log = LoggerFactory.getLogger(PreparationFailureRecorder.class);
	private static final int MAX_MESSAGE = 2000;

	private final TransactionTemplate transactionTemplate;
	private final MigrationRunRepository migrationRunRepository;
	private final MigrationBatchRepository migrationBatchRepository;
	private final MigrationErrorRepository migrationErrorRepository;

	public PreparationFailureRecorder(
			PlatformTransactionManager transactionManager,
			MigrationRunRepository migrationRunRepository,
			MigrationBatchRepository migrationBatchRepository,
			MigrationErrorRepository migrationErrorRepository) {
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.migrationRunRepository = migrationRunRepository;
		this.migrationBatchRepository = migrationBatchRepository;
		this.migrationErrorRepository = migrationErrorRepository;
	}

	public void record(UUID runId, RuntimeException failure) {
		if (failure instanceof InvalidStateTransitionException || failure instanceof MigrationRunNotFoundException) {
			return;
		}
		try {
			transactionTemplate.executeWithoutResult(status -> write(runId, failure));
		} catch (RuntimeException writeFailure) {
			log.warn("Could not record preparation failure runId={} exceptionType={}",
					runId, failure.getClass().getName());
		}
	}

	private void write(UUID runId, RuntimeException failure) {
		MigrationRun run = migrationRunRepository.findById(runId).orElse(null);
		if (run == null || prepared(run)) {
			return;
		}
		MigrationRunStatus status = run.getStatus();
		if (status != MigrationRunStatus.QUEUED && status != MigrationRunStatus.RUNNING) {
			return;
		}
		long inFlight = migrationBatchRepository.countByMigrationRun_IdAndStatusIn(
				runId,
				EnumSet.of(
						MigrationBatchStatus.PENDING,
						MigrationBatchStatus.RETRY_PENDING,
						MigrationBatchStatus.CLAIMED,
						MigrationBatchStatus.PROCESSING));
		String code = errorCode(failure);
		String message = message(failure);
		String raw = failure.getMessage();
		if (raw == null || raw.isBlank()) {
			log.warn("Preparation failed runId={} errorCode={} exceptionType={} cause={}",
					runId, code, failure.getClass().getName(), TargetFailureDiagnostics.describe(failure), failure);
		} else {
			log.warn("Preparation failed runId={} errorCode={} exceptionType={} cause={}",
					runId, code, failure.getClass().getName(), TargetFailureDiagnostics.describe(failure));
		}
		String targetEntity = run.getMigrationDefinition() == null
				? null
				: run.getMigrationDefinition().getTargetEntity();
		boolean alreadyRecorded = migrationErrorRepository
				.existsByMigrationRun_IdAndMigrationBatchIsNullAndStageAndErrorCodeAndErrorMessageAndSourceRecordKeyIsNull(
						runId, MigrationStage.EXTRACT, code, message);
		if (!alreadyRecorded) {
			MigrationError error = new MigrationError();
			error.setMigrationRun(run);
			error.setStage(MigrationStage.EXTRACT);
			error.setErrorCode(code);
			error.setErrorType("PREPARATION_FAILED");
			error.setErrorMessage(message);
			java.util.Map<String, Object> details = MigrationErrorDetails.collect(
					failure, code, "PREPARE", runId, null, targetEntity, false);
			details.put("category", "CONFIGURATION");
			details.put("location", TargetFailureDiagnostics.location(failure));
			error.setErrorDetails(MigrationErrorDetails.publicDetails(details));
			error.setRetryable(false);
			migrationErrorRepository.save(error);
		}
		if (inFlight > 0) {
			return;
		}
		Instant now = Instant.now();
		if (status == MigrationRunStatus.QUEUED) {
			MigrationRunTransitions.require(MigrationRunStatus.QUEUED, MigrationRunStatus.RUNNING);
			run.setStatus(MigrationRunStatus.RUNNING);
		}
		if (run.getStartedAt() == null) {
			run.setStartedAt(now);
		}
		MigrationRunTransitions.require(MigrationRunStatus.RUNNING, MigrationRunStatus.FAILED);
		run.setStatus(MigrationRunStatus.FAILED);
		run.setErrorMessage(message);
		run.setCompletedAt(now);
		run.setUpdatedAt(now);
	}

	private static boolean prepared(MigrationRun run) {
		Object statusMessage = run.getSourceSnapshot() == null
				? null
				: run.getSourceSnapshot().get("statusMessage");
		return SourceInspectionService.STATUS_SOURCE_INSPECTED.equals(statusMessage);
	}

	private static String errorCode(Throwable failure) {
		Throwable current = failure;
		for (int depth = 0; current != null && depth < 8; depth++) {
			if (current instanceof SftpSourceException sftp) {
				return sftp.errorCode();
			}
			if (current instanceof BatchProcessingException batch) {
				return batch.errorCode();
			}
			if (current instanceof TargetInfrastructureException target) {
				return target.errorCode();
			}
			current = current.getCause();
		}
		return MigrationErrorCodes.PREPARATION_FAILED;
	}

	private static String message(RuntimeException failure) {
		String diagnostic = MigrationErrorDetails.diagnostic(failure);
		return diagnostic.length() <= MAX_MESSAGE ? diagnostic : diagnostic.substring(0, MAX_MESSAGE);
	}
}
