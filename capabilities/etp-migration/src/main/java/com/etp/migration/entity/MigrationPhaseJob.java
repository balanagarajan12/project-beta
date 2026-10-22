package com.etp.migration.entity;

import com.etp.migration.run.MigrationPhase;
import com.etp.migration.run.PhaseJobStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "migration_phase_job")
public class MigrationPhaseJob {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	@JdbcTypeCode(SqlTypes.UUID)
	private UUID id;

	@Column(name = "migration_run_id", nullable = false, updatable = false)
	@JdbcTypeCode(SqlTypes.UUID)
	private UUID migrationRunId;

	@Enumerated(EnumType.STRING)
	@Column(name = "phase", nullable = false, updatable = false, length = 40)
	private MigrationPhase phase;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private PhaseJobStatus status;

	@Column(name = "scope", length = 40)
	private String scope;

	@Column(name = "promotion_id")
	@JdbcTypeCode(SqlTypes.UUID)
	private UUID promotionId;

	/** Written only by {@code MigrationPhaseClaim}. */
	@Column(name = "worker_id", length = 200, updatable = false)
	private String workerId;

	@Column(name = "lease_until", updatable = false)
	private Instant leaseUntil;

	@Column(name = "attempt_count", nullable = false)
	private int attemptCount;

	@Column(name = "outcome", length = 40)
	private String outcome;

	@Column(name = "error_code", length = 100)
	private String errorCode;

	@Column(name = "error_message", length = 2000)
	private String errorMessage;

	@Column(name = "requested_by", length = 100, updatable = false)
	private String requestedBy;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "progress_at")
	private Instant progressAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@PrePersist
	void onCreate() {
		Instant now = Instant.now();
		if (createdAt == null) {
			createdAt = now;
		}
		updatedAt = now;
	}

	@PreUpdate
	void onUpdate() {
		updatedAt = Instant.now();
	}

	public UUID getId() {
		return id;
	}

	public void setId(UUID id) {
		this.id = id;
	}

	public UUID getMigrationRunId() {
		return migrationRunId;
	}

	public void setMigrationRunId(UUID migrationRunId) {
		this.migrationRunId = migrationRunId;
	}

	public MigrationPhase getPhase() {
		return phase;
	}

	public void setPhase(MigrationPhase phase) {
		this.phase = phase;
	}

	public PhaseJobStatus getStatus() {
		return status;
	}

	public void setStatus(PhaseJobStatus status) {
		this.status = status;
	}

	public String getScope() {
		return scope;
	}

	public void setScope(String scope) {
		this.scope = scope;
	}

	public UUID getPromotionId() {
		return promotionId;
	}

	public void setPromotionId(UUID promotionId) {
		this.promotionId = promotionId;
	}

	public Instant getLeaseUntil() {
		return leaseUntil;
	}

	public int getAttemptCount() {
		return attemptCount;
	}

	public void setAttemptCount(int attemptCount) {
		this.attemptCount = attemptCount;
	}

	public String getOutcome() {
		return outcome;
	}

	public void setOutcome(String outcome) {
		this.outcome = outcome;
	}

	public String getErrorCode() {
		return errorCode;
	}

	public void setErrorCode(String errorCode) {
		this.errorCode = errorCode;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public void setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
	}

	public String getRequestedBy() {
		return requestedBy;
	}

	public void setRequestedBy(String requestedBy) {
		this.requestedBy = requestedBy;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public Instant getCompletedAt() {
		return completedAt;
	}
}
