package com.etp.migration.processing;

import com.etp.migration.connector.target.TargetFailureDiagnostics;
import com.etp.migration.entity.MigrationError;
import com.etp.migration.entity.MigrationPromotionBatch;
import com.etp.migration.entity.MigrationRun;
import com.etp.migration.entity.MigrationStage;
import com.etp.migration.promotion.PromotionException;
import com.etp.migration.repository.MigrationErrorRepository;
import com.etp.migration.repository.MigrationRunRepository;
import com.etp.migration.run.MigrationPhase;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes one {@code migration_error} row for a promotion-batch or phase failure.
 *
 * <p>The row is written in the caller's transaction when one is open. Promotion calls this after the target
 * transaction has already rolled back, inside the transaction that stores the batch's FAILED status. A repeated
 * failure with the same run, batch, stage, code, and message does not insert another row. Stored rows are not
 * removed when a later attempt succeeds. Stack traces stay in the log.
 */
@Component
public class OperationErrorRecorder {

	private static final Logger log = LoggerFactory.getLogger(OperationErrorRecorder.class);
	private static final int MAX_MESSAGE = 2000;

	private final MigrationErrorRepository errors;
	private final MigrationRunRepository runs;

	public OperationErrorRecorder(MigrationErrorRepository errors, MigrationRunRepository runs) {
		this.errors = errors;
		this.runs = runs;
	}

	@Transactional
	public void recordPromotionBatch(MigrationPromotionBatch batch, RuntimeException failure) {
		try {
			writePromotionBatch(batch, failure);
		} catch (RuntimeException writeFailure) {
			log.warn("Promotion error detail was not stored promotionBatchId={} exceptionType={}",
					batch.getId(), writeFailure.getClass().getName());
		}
	}

	@Transactional
	public void recordPhase(
			UUID runId,
			UUID jobId,
			MigrationPhase phase,
			String errorCode,
			String errorMessage,
			RuntimeException failure,
			boolean retryable) {
		try {
			writePhase(runId, jobId, phase, errorCode, errorMessage, failure, retryable);
		} catch (RuntimeException writeFailure) {
			log.warn("Phase error detail was not stored jobId={} exceptionType={}",
					jobId, writeFailure.getClass().getName());
		}
	}

	private void writePromotionBatch(MigrationPromotionBatch batch, RuntimeException failure) {
		Long batchId = batch.getMigrationBatchId();
		if (batch.getMigrationRun() == null || batch.getMigrationRun().getId() == null || batchId == null) {
			log.warn("Promotion error detail was not stored promotionBatchId={} because the run or batch is missing",
					batch.getId());
			return;
		}
		UUID runId = batch.getMigrationRun().getId();
		String code = batch.getErrorCode();
		String message = limit(batch.getErrorMessage());
		if (errors.existsByMigrationRun_IdAndMigrationBatch_IdAndStageAndErrorCodeAndErrorMessageAndSourceRecordKeyIsNull(
				runId, batchId, MigrationStage.PROMOTE, code, message)) {
			return;
		}
		MigrationError error = new MigrationError();
		error.setMigrationRun(batch.getMigrationRun());
		error.setMigrationBatch(batch.getMigrationBatch());
		error.setStage(MigrationStage.PROMOTE);
		error.setErrorCode(code);
		error.setErrorType("PROMOTION_BATCH");
		error.setErrorMessage(message);
		boolean retryable = !(failure instanceof PromotionException);
		Map<String, Object> details = MigrationErrorDetails.collect(
				failure, code, "PROMOTE", runId, batchId, null, retryable);
		details.put("category", retryable ? "BATCH" : "CONFIGURATION");
		details.put("location", TargetFailureDiagnostics.location(failure));
		if (batch.getPromotion() != null && batch.getPromotion().getId() != null) {
			details.put("promotionId", batch.getPromotion().getId().toString());
		}
		if (batch.getId() != null) {
			details.put("promotionBatchId", batch.getId());
		}
		error.setErrorDetails(MigrationErrorDetails.publicDetails(details));
		error.setRetryable(retryable);
		errors.save(error);
	}

	private void writePhase(
			UUID runId,
			UUID jobId,
			MigrationPhase phase,
			String errorCode,
			String errorMessage,
			RuntimeException failure,
			boolean retryable) {
		MigrationStage stage = phase == MigrationPhase.RECONCILIATION ? MigrationStage.RECONCILE : MigrationStage.PROMOTE;
		String message = limit(errorMessage);
		if (errors.existsByMigrationRun_IdAndMigrationBatchIsNullAndStageAndErrorCodeAndErrorMessageAndSourceRecordKeyIsNull(
				runId, stage, errorCode, message)) {
			return;
		}
		MigrationRun run = runs.findById(runId).orElse(null);
		if (run == null) {
			log.warn("Phase error detail was not stored jobId={} because the run is missing", jobId);
			return;
		}
		MigrationError error = new MigrationError();
		error.setMigrationRun(run);
		error.setStage(stage);
		error.setErrorCode(errorCode);
		error.setErrorType("PHASE_FAILED");
		error.setErrorMessage(message);
		Map<String, Object> details = MigrationErrorDetails.collect(
				failure, errorCode, phase.name(), runId, null, null, retryable);
		details.put("category", retryable ? "PHASE" : "CONFIGURATION");
		details.put("location", TargetFailureDiagnostics.location(failure));
		if (jobId != null) {
			details.put("phaseJobId", jobId.toString());
		}
		error.setErrorDetails(MigrationErrorDetails.publicDetails(details));
		error.setRetryable(retryable);
		errors.save(error);
	}

	private static String limit(String message) {
		if (message == null || message.isBlank()) {
			return "Operation failed";
		}
		return message.length() <= MAX_MESSAGE ? message : message.substring(0, MAX_MESSAGE);
	}
}
