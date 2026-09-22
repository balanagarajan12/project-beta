package com.etp.migration.batch;

import com.etp.migration.config.WorkerIdentity;
import com.etp.migration.entity.MigrationBatch;
import com.etp.migration.entity.MigrationBatchStatus;
import com.etp.migration.entity.MigrationRun;
import com.etp.migration.entity.MigrationRunStatus;
import com.etp.migration.repository.MigrationBatchRepository;
import com.etp.migration.run.InvalidStateTransitionException;
import com.etp.migration.run.MigrationRunTransitions;
import com.etp.migration.run.RunCompletionEvaluator;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MigrationBatchQueueService {

	private static final String CLAIM_SQL = """
			SELECT b.id
			FROM migration_batch b
			INNER JOIN migration_run r ON r.id = b.migration_run_id
			WHERE r.status IN ('QUEUED', 'RUNNING')
			  AND (
			    b.status IN ('PENDING', 'RETRY_PENDING')
			    OR (
			      b.status IN ('CLAIMED', 'PROCESSING')
			      AND b.lease_until IS NOT NULL
			      AND b.lease_until < ?
			    )
			  )
			ORDER BY r.created_at ASC, b.batch_number ASC
			FOR UPDATE OF b SKIP LOCKED
			LIMIT 1
			""";

	private final EntityManager entityManager;
	private final JdbcTemplate jdbcTemplate;
	private final MigrationBatchRepository migrationBatchRepository;
	private final WorkerIdentity workerIdentity;
	private final RunCompletionEvaluator runCompletionEvaluator;

	public MigrationBatchQueueService(
			EntityManager entityManager,
			JdbcTemplate jdbcTemplate,
			MigrationBatchRepository migrationBatchRepository,
			WorkerIdentity workerIdentity,
			RunCompletionEvaluator runCompletionEvaluator) {
		this.entityManager = entityManager;
		this.jdbcTemplate = jdbcTemplate;
		this.migrationBatchRepository = migrationBatchRepository;
		this.workerIdentity = workerIdentity;
		this.runCompletionEvaluator = runCompletionEvaluator;
	}

	@Transactional
	public Optional<MigrationBatch> claimNextBatch() {
		return claimNextBatch(workerIdentity.workerId(), null);
	}

	@Transactional
	public Optional<MigrationBatch> claimNextBatch(String workerId) {
		return claimNextBatch(workerId, null);
	}

	@Transactional
	public Optional<MigrationBatch> claimNextBatchForRun(UUID runId, String workerId) {
		return claimNextBatch(workerId, runId);
	}

	private Optional<MigrationBatch> claimNextBatch(String workerId, UUID runId) {
		Instant now = Instant.now();
		entityManager.flush();
		String sql = CLAIM_SQL;
		List<Long> ids;
		if (runId == null) {
			ids = jdbcTemplate.query(
					sql,
					ps -> ps.setTimestamp(1, Timestamp.from(now)),
					(rs, rowNum) -> rs.getLong(1));
		} else {
			sql = CLAIM_SQL.replace(
					"WHERE r.status IN ('QUEUED', 'RUNNING')",
					"WHERE r.id = ? AND r.status IN ('QUEUED', 'RUNNING')");
			ids = jdbcTemplate.query(
					sql,
					ps -> {
						ps.setObject(1, runId);
						ps.setTimestamp(2, Timestamp.from(now));
					},
					(rs, rowNum) -> rs.getLong(1));
		}
		if (ids.isEmpty()) {
			return Optional.empty();
		}
		Long batchId = ids.get(0);
		MigrationBatch batch = migrationBatchRepository.findById(batchId)
				.orElseThrow(() -> new MigrationBatchNotFoundException(batchId));

		MigrationBatchStatus current = batch.getStatus();
		if (current == MigrationBatchStatus.PENDING || current == MigrationBatchStatus.RETRY_PENDING) {
			MigrationBatchTransitions.require(current, MigrationBatchStatus.CLAIMED);
		} else if (current == MigrationBatchStatus.PROCESSING) {
			MigrationBatchTransitions.require(MigrationBatchStatus.PROCESSING, MigrationBatchStatus.CLAIMED);
		} else if (current != MigrationBatchStatus.CLAIMED) {
			throw new InvalidStateTransitionException("Cannot claim batch in status " + current);
		}
		batch.setStatus(MigrationBatchStatus.CLAIMED);
		batch.setWorkerId(workerId);
		batch.setClaimedAt(now);
		batch.setLeaseUntil(now.plus(workerIdentity.leaseDuration()));
		batch.setAttemptCount(batch.getAttemptCount() + 1);
		batch.setUpdatedAt(now);
		batch.setErrorMessage(null);

		markRunRunning(batch.getMigrationRun(), now);
		return Optional.of(batch);
	}

	@Transactional
	public MigrationBatch renewLease(Long batchId, String workerId) {
		MigrationBatch batch = requireBatch(batchId);
		if (batch.getWorkerId() == null || !batch.getWorkerId().equals(workerId)) {
			throw new InvalidStateTransitionException("Batch " + batchId + " is not claimed by worker " + workerId);
		}
		if (batch.getStatus() != MigrationBatchStatus.CLAIMED && batch.getStatus() != MigrationBatchStatus.PROCESSING) {
			throw new InvalidStateTransitionException("Cannot renew lease for batch in status " + batch.getStatus());
		}
		Instant now = Instant.now();
		batch.setLeaseUntil(now.plus(workerIdentity.leaseDuration()));
		batch.setUpdatedAt(now);
		return batch;
	}

	@Transactional
	public MigrationBatch beginProcessing(Long batchId) {
		MigrationBatch batch = requireBatch(batchId);
		MigrationBatchTransitions.require(batch.getStatus(), MigrationBatchStatus.PROCESSING);
		Instant now = Instant.now();
		batch.setStatus(MigrationBatchStatus.PROCESSING);
		if (batch.getStartedAt() == null) {
			batch.setStartedAt(now);
		}
		batch.setUpdatedAt(now);
		markRunRunning(batch.getMigrationRun(), now);
		return batch;
	}

	@Transactional
	public MigrationBatch completeBatch(Long batchId, BatchCompletionCounters counters, boolean withErrors) {
		MigrationBatch batch = requireBatch(batchId);
		MigrationBatchStatus target = withErrors
				? MigrationBatchStatus.COMPLETED_WITH_ERRORS
				: MigrationBatchStatus.COMPLETED;
		if (batch.getStatus() == MigrationBatchStatus.CLAIMED) {
			MigrationBatchTransitions.require(MigrationBatchStatus.CLAIMED, MigrationBatchStatus.PROCESSING);
			batch.setStatus(MigrationBatchStatus.PROCESSING);
		}
		MigrationBatchTransitions.require(batch.getStatus(), target);
		Instant now = Instant.now();
		applyCounters(batch, counters);
		batch.setStatus(target);
		batch.setCompletedAt(now);
		batch.setLeaseUntil(null);
		batch.setUpdatedAt(now);
		applyRunCounters(batch.getMigrationRun(), counters, target, now);
		markRunRunning(batch.getMigrationRun(), now);
		runCompletionEvaluator.evaluate(batch.getMigrationRun(), now);
		return batch;
	}

	@Transactional
	public MigrationBatch failBatch(Long batchId, String errorMessage) {
		MigrationBatch batch = requireBatch(batchId);
		if (batch.getStatus() == MigrationBatchStatus.CLAIMED) {
			MigrationBatchTransitions.require(MigrationBatchStatus.CLAIMED, MigrationBatchStatus.PROCESSING);
			batch.setStatus(MigrationBatchStatus.PROCESSING);
		}
		MigrationBatchTransitions.require(batch.getStatus(), MigrationBatchStatus.FAILED);
		Instant now = Instant.now();
		batch.setStatus(MigrationBatchStatus.FAILED);
		batch.setErrorMessage(errorMessage);
		batch.setCompletedAt(now);
		batch.setLeaseUntil(null);
		batch.setUpdatedAt(now);
		MigrationRun run = batch.getMigrationRun();
		run.setFailedBatches(run.getFailedBatches() + 1);
		run.setUpdatedAt(now);
		markRunRunning(run, now);
		runCompletionEvaluator.evaluate(run, now);
		return batch;
	}

	@Transactional
	public MigrationBatch retryBatch(Long batchId) {
		MigrationBatch batch = requireBatch(batchId);
		MigrationBatchTransitions.require(batch.getStatus(), MigrationBatchStatus.RETRY_PENDING);
		Instant now = Instant.now();
		reverseRunContribution(batch.getMigrationRun(), batch, now);
		batch.setStatus(MigrationBatchStatus.RETRY_PENDING);
		batch.setWorkerId(null);
		batch.setClaimedAt(null);
		batch.setLeaseUntil(null);
		batch.setCompletedAt(null);
		batch.setErrorMessage(null);
		batch.setProcessedCount(0);
		batch.setSuccessCount(0);
		batch.setDuplicateCount(0);
		batch.setValidationFailureCount(0);
		batch.setEnrichmentFailureCount(0);
		batch.setLoadFailureCount(0);
		batch.setRejectedCount(0);
		batch.setUpdatedAt(now);
		return batch;
	}

	public static void reverseRunContribution(MigrationRun run, MigrationBatch batch, Instant now) {
		run.setProcessedRecords(Math.max(0, run.getProcessedRecords() - nvl(batch.getProcessedCount())));
		run.setSuccessRecords(Math.max(0, run.getSuccessRecords() - nvl(batch.getSuccessCount())));
		run.setDuplicateRecords(Math.max(0, run.getDuplicateRecords() - nvl(batch.getDuplicateCount())));
		run.setValidationFailures(Math.max(0, run.getValidationFailures() - nvl(batch.getValidationFailureCount())));
		run.setEnrichmentFailures(Math.max(0, run.getEnrichmentFailures() - nvl(batch.getEnrichmentFailureCount())));
		run.setLoadFailures(Math.max(0, run.getLoadFailures() - nvl(batch.getLoadFailureCount())));
		run.setRejectedRecords(Math.max(0, run.getRejectedRecords() - nvl(batch.getRejectedCount())));
		if (batch.getStatus() == MigrationBatchStatus.COMPLETED
				|| batch.getStatus() == MigrationBatchStatus.COMPLETED_WITH_ERRORS) {
			run.setCompletedBatches(Math.max(0, run.getCompletedBatches() - 1));
		}
		if (batch.getStatus() == MigrationBatchStatus.FAILED
				|| batch.getStatus() == MigrationBatchStatus.COMPLETED_WITH_ERRORS) {
			run.setFailedBatches(Math.max(0, run.getFailedBatches() - 1));
		}
		run.setUpdatedAt(now);
	}

	private static int nvl(Integer value) {
		return value == null ? 0 : value;
	}

	private MigrationBatch requireBatch(Long batchId) {
		return migrationBatchRepository.findById(batchId)
				.orElseThrow(() -> new MigrationBatchNotFoundException(batchId));
	}

	private void applyCounters(MigrationBatch batch, BatchCompletionCounters counters) {
		batch.setProcessedCount(counters.processedCount());
		batch.setSuccessCount(counters.successCount());
		batch.setDuplicateCount(counters.duplicateCount());
		batch.setValidationFailureCount(counters.validationFailureCount());
		batch.setEnrichmentFailureCount(counters.enrichmentFailureCount());
		batch.setLoadFailureCount(counters.loadFailureCount());
		batch.setRejectedCount(counters.rejectedCount());
	}

	private void applyRunCounters(
			MigrationRun run,
			BatchCompletionCounters counters,
			MigrationBatchStatus batchStatus,
			Instant now) {
		run.setProcessedRecords(run.getProcessedRecords() + counters.processedCount());
		run.setSuccessRecords(run.getSuccessRecords() + counters.successCount());
		run.setDuplicateRecords(run.getDuplicateRecords() + counters.duplicateCount());
		run.setValidationFailures(run.getValidationFailures() + counters.validationFailureCount());
		run.setEnrichmentFailures(run.getEnrichmentFailures() + counters.enrichmentFailureCount());
		run.setLoadFailures(run.getLoadFailures() + counters.loadFailureCount());
		run.setRejectedRecords(run.getRejectedRecords() + counters.rejectedCount());
		run.setCompletedBatches(run.getCompletedBatches() + 1);
		if (batchStatus == MigrationBatchStatus.COMPLETED_WITH_ERRORS) {
			run.setFailedBatches(run.getFailedBatches() + 1);
		}
		run.setUpdatedAt(now);
	}

	private void markRunRunning(MigrationRun run, Instant now) {
		if (run.getStatus() == MigrationRunStatus.QUEUED) {
			MigrationRunTransitions.require(MigrationRunStatus.QUEUED, MigrationRunStatus.RUNNING);
			run.setStatus(MigrationRunStatus.RUNNING);
			if (run.getStartedAt() == null) {
				run.setStartedAt(now);
			}
			run.setUpdatedAt(now);
		}
	}
}
