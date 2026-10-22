package com.etp.migration.run;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Atomic claim for a background execution. The run row is the queue.
 * A manual {@code POST /runs} run is not claimed because {@code background_execution} stays false
 * until {@code POST /execute} accepts it.
 */
@Component
public class MigrationExecutionClaim {

	private final JdbcTemplate jdbcTemplate;
	private final MigrationExecutionProperties properties;
	private final String workerId;

	public MigrationExecutionClaim(
			JdbcTemplate jdbcTemplate,
			MigrationExecutionProperties properties,
			com.etp.migration.config.WorkerIdentity workerIdentity) {
		this.jdbcTemplate = jdbcTemplate;
		this.properties = properties;
		this.workerId = workerIdentity.workerId();
	}

	@Transactional
	public boolean tryClaim(UUID runId) {
		Instant now = Instant.now();
		Instant until = now.plus(properties.leaseDuration());
		int updated = jdbcTemplate.update("""
				UPDATE migration_run
				SET execution_worker_id = ?,
				    execution_lease_until = ?,
				    background_execution = TRUE,
				    status = 'RUNNING',
				    started_at = COALESCE(started_at, ?),
				    updated_at = ?
				WHERE id = ?
				  AND status IN ('QUEUED', 'RUNNING')
				  AND background_execution = TRUE
				  AND (execution_lease_until IS NULL OR execution_lease_until < ?)
				""",
				workerId,
				Timestamp.from(until),
				Timestamp.from(now),
				Timestamp.from(now),
				runId,
				Timestamp.from(now));
		return updated == 1;
	}

	/**
	 * Extends the lease when this worker still owns a non-terminal run.
	 * A run this worker already finished is {@link Ownership#FINISHED}, not a lost lease:
	 * the batch worker marks the run terminal before the driver asks for the next batch.
	 */
	@Transactional
	public Ownership keep(UUID runId) {
		if (renew(runId)) {
			return Ownership.ACTIVE;
		}
		String status = statusOwnedByThisWorker(runId);
		if (status == null) {
			return Ownership.LOST;
		}
		try {
			if (com.etp.migration.run.MigrationRunTransitions.isTerminal(
					com.etp.migration.entity.MigrationRunStatus.valueOf(status))) {
				return Ownership.FINISHED;
			}
		} catch (IllegalArgumentException ex) {
			return Ownership.LOST;
		}
		return Ownership.ACTIVE;
	}

	@Transactional
	public boolean renew(UUID runId) {
		Instant now = Instant.now();
		Instant until = now.plus(properties.leaseDuration());
		int updated = jdbcTemplate.update("""
				UPDATE migration_run
				SET execution_lease_until = ?,
				    updated_at = ?
				WHERE id = ?
				  AND execution_worker_id = ?
				  AND status IN ('QUEUED', 'RUNNING')
				""",
				Timestamp.from(until),
				Timestamp.from(now),
				runId,
				workerId);
		return updated == 1;
	}

	@Transactional
	public void release(UUID runId) {
		jdbcTemplate.update("""
				UPDATE migration_run
				SET execution_worker_id = NULL,
				    execution_lease_until = NULL,
				    updated_at = ?
				WHERE id = ?
				  AND execution_worker_id = ?
				""",
				Timestamp.from(Instant.now()),
				runId,
				workerId);
	}

	private String statusOwnedByThisWorker(UUID runId) {
		List<String> statuses = jdbcTemplate.query("""
				SELECT status
				FROM migration_run
				WHERE id = ?
				  AND execution_worker_id = ?
				""",
				(rs, row) -> rs.getString("status"),
				runId,
				workerId);
		return statuses.isEmpty() ? null : statuses.get(0);
	}

	@Transactional(readOnly = true)
	public List<UUID> recoverable(Instant now) {
		return jdbcTemplate.query("""
				SELECT id
				FROM migration_run
				WHERE background_execution = TRUE
				  AND status IN ('QUEUED', 'RUNNING')
				  AND control_state NOT IN ('PAUSE_REQUESTED', 'PAUSED')
				  AND (execution_lease_until IS NULL OR execution_lease_until < ?)
				ORDER BY created_at
				LIMIT 32
				""",
				(rs, row) -> rs.getObject("id", UUID.class),
				Timestamp.from(now));
	}

	public enum Ownership {
		ACTIVE,
		FINISHED,
		LOST
	}
}
