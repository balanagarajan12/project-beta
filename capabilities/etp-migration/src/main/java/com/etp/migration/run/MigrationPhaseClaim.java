package com.etp.migration.run;

import com.etp.migration.config.WorkerIdentity;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Claims one phase job. The same conditional update used for migration execution
 * keeps two workers from running the same reconciliation or promotion.
 */
@Component
public class MigrationPhaseClaim {

	private final JdbcTemplate jdbcTemplate;
	private final MigrationExecutionProperties properties;
	private final String workerId;

	public MigrationPhaseClaim(
			JdbcTemplate jdbcTemplate,
			MigrationExecutionProperties properties,
			WorkerIdentity workerIdentity) {
		this.jdbcTemplate = jdbcTemplate;
		this.properties = properties;
		this.workerId = workerIdentity.workerId();
	}

	@Transactional
	public boolean tryClaim(UUID jobId) {
		Instant now = Instant.now();
		Instant until = now.plus(properties.leaseDuration());
		int updated = jdbcTemplate.update("""
				UPDATE migration_phase_job
				SET worker_id = ?,
				    lease_until = ?,
				    status = 'RUNNING',
				    started_at = COALESCE(started_at, ?),
				    progress_at = ?,
				    attempt_count = attempt_count + 1,
				    updated_at = ?
				WHERE id = ?
				  AND status IN ('QUEUED', 'RUNNING')
				  AND (lease_until IS NULL OR lease_until < ?)
				""",
				workerId,
				Timestamp.from(until),
				Timestamp.from(now),
				Timestamp.from(now),
				Timestamp.from(now),
				jobId,
				Timestamp.from(now));
		return updated == 1;
	}

	/**
	 * True only while this worker's lease is still in the future.
	 * A silent worker keeps the job until that lease expires.
	 */
	@Transactional(readOnly = true)
	public boolean holdsValidLease(UUID jobId) {
		Integer count = jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM migration_phase_job
				WHERE id = ?
				  AND worker_id = ?
				  AND status IN ('QUEUED', 'RUNNING')
				  AND lease_until IS NOT NULL
				  AND lease_until >= ?
				""",
				Integer.class,
				jobId,
				workerId,
				Timestamp.from(Instant.now()));
		return count != null && count == 1;
	}

	@Transactional
	public boolean renew(UUID jobId) {
		Instant now = Instant.now();
		int updated = jdbcTemplate.update("""
				UPDATE migration_phase_job
				SET lease_until = ?,
				    updated_at = ?
				WHERE id = ?
				  AND worker_id = ?
				  AND status IN ('QUEUED', 'RUNNING')
				""",
				Timestamp.from(now.plus(properties.leaseDuration())),
				Timestamp.from(now),
				jobId,
				workerId);
		return updated == 1;
	}

	@Transactional
	public boolean complete(UUID jobId, String outcome, UUID promotionId) {
		Instant now = Instant.now();
		int updated = jdbcTemplate.update("""
				UPDATE migration_phase_job
				SET status = 'COMPLETED',
				    outcome = ?,
				    promotion_id = COALESCE(?, promotion_id),
				    completed_at = ?,
				    progress_at = ?,
				    error_code = NULL,
				    error_message = NULL,
				    updated_at = ?
				WHERE id = ?
				  AND worker_id = ?
				  AND status IN ('QUEUED', 'RUNNING')
				""",
				outcome,
				promotionId,
				Timestamp.from(now),
				Timestamp.from(now),
				Timestamp.from(now),
				jobId,
				workerId);
		return updated == 1;
	}

	/**
	 * Records a failure only while this worker still owns a non-terminal job.
	 * A completed job is left completed when a later recovery attempt fails.
	 */
	@Transactional
	public boolean fail(UUID jobId, String errorCode, String errorMessage) {
		Instant now = Instant.now();
		int updated = jdbcTemplate.update("""
				UPDATE migration_phase_job
				SET status = 'FAILED',
				    error_code = ?,
				    error_message = ?,
				    completed_at = ?,
				    updated_at = ?
				WHERE id = ?
				  AND worker_id = ?
				  AND status IN ('QUEUED', 'RUNNING')
				""",
				errorCode,
				truncate(errorMessage),
				Timestamp.from(now),
				Timestamp.from(now),
				jobId,
				workerId);
		return updated == 1;
	}

	@Transactional
	public void release(UUID jobId) {
		jdbcTemplate.update("""
				UPDATE migration_phase_job
				SET worker_id = NULL,
				    lease_until = NULL,
				    updated_at = ?
				WHERE id = ?
				  AND worker_id = ?
				""",
				Timestamp.from(Instant.now()),
				jobId,
				workerId);
	}

	@Transactional(readOnly = true)
	public List<UUID> recoverable(Instant now) {
		return jdbcTemplate.query("""
				SELECT j.id
				FROM migration_phase_job j
				WHERE j.status IN ('QUEUED', 'RUNNING')
				  AND (j.lease_until IS NULL OR j.lease_until < ?)
				  AND NOT (
				      j.phase = 'PROMOTION'
				      AND EXISTS (
				          SELECT 1
				          FROM migration_promotion p
				          WHERE p.migration_run_id = j.migration_run_id
				            AND p.status = 'PROMOTING'
				            AND p.control_state IN ('PAUSE_REQUESTED', 'PAUSED')
				      )
				  )
				ORDER BY j.created_at
				LIMIT 32
				""",
				(rs, row) -> rs.getObject("id", UUID.class),
				Timestamp.from(now));
	}

	private static String truncate(String message) {
		if (message == null || message.isBlank()) {
			return "Phase job failed";
		}
		return message.length() <= 2000 ? message : message.substring(0, 2000);
	}
}
