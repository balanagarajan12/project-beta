package com.etp.migration.entity;

import com.etp.migration.run.ExecutionControlState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "migration_promotion")
public class MigrationPromotion {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	@JdbcTypeCode(SqlTypes.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "migration_run_id", nullable = false)
	private MigrationRun migrationRun;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 30)
	private PromotionStatus status = PromotionStatus.NOT_STARTED;

	@Column(name = "requested_by", length = 100)
	private String requestedBy;

	@Column(name = "requested_at")
	private Instant requestedAt;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Column(name = "total_batches", nullable = false)
	private Integer totalBatches = 0;

	@Column(name = "eligible_batches", nullable = false)
	private Integer eligibleBatches = 0;

	@Column(name = "promoted_batches", nullable = false)
	private Integer promotedBatches = 0;

	@Column(name = "failed_batches", nullable = false)
	private Integer failedBatches = 0;

	@Column(name = "skipped_batches", nullable = false)
	private Integer skippedBatches = 0;

	@Column(name = "total_records", nullable = false)
	private Long totalRecords = 0L;

	@Column(name = "promoted_records", nullable = false)
	private Long promotedRecords = 0L;

	@Column(name = "failed_records", nullable = false)
	private Long failedRecords = 0L;

	@Column(name = "skipped_records", nullable = false)
	private Long skippedRecords = 0L;

	@Column(name = "error_code", length = 100)
	private String errorCode;

	@Column(name = "error_message", length = 2000)
	private String errorMessage;

	@Enumerated(EnumType.STRING)
	@Column(name = "control_state", nullable = false, length = 20)
	private ExecutionControlState controlState = ExecutionControlState.RUNNING;

	@Column(name = "control_updated_at")
	private Instant controlUpdatedAt;

	@Column(name = "last_control_action", length = 20)
	private String lastControlAction;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public MigrationPromotion() {
	}

	@PrePersist
	void onCreate() {
		if (id == null) {
			id = UUID.randomUUID();
		}
		Instant now = Instant.now();
		if (createdAt == null) {
			createdAt = now;
		}
		if (updatedAt == null) {
			updatedAt = now;
		}
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

	public MigrationRun getMigrationRun() {
		return migrationRun;
	}

	public void setMigrationRun(MigrationRun migrationRun) {
		this.migrationRun = migrationRun;
	}

	public PromotionStatus getStatus() {
		return status;
	}

	public void setStatus(PromotionStatus status) {
		this.status = status;
	}

	public String getRequestedBy() {
		return requestedBy;
	}

	public void setRequestedBy(String requestedBy) {
		this.requestedBy = requestedBy;
	}

	public Instant getRequestedAt() {
		return requestedAt;
	}

	public void setRequestedAt(Instant requestedAt) {
		this.requestedAt = requestedAt;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public void setStartedAt(Instant startedAt) {
		this.startedAt = startedAt;
	}

	public Instant getCompletedAt() {
		return completedAt;
	}

	public void setCompletedAt(Instant completedAt) {
		this.completedAt = completedAt;
	}

	public Integer getTotalBatches() {
		return totalBatches;
	}

	public void setTotalBatches(Integer totalBatches) {
		this.totalBatches = totalBatches;
	}

	public Integer getEligibleBatches() {
		return eligibleBatches;
	}

	public void setEligibleBatches(Integer eligibleBatches) {
		this.eligibleBatches = eligibleBatches;
	}

	public Integer getPromotedBatches() {
		return promotedBatches;
	}

	public void setPromotedBatches(Integer promotedBatches) {
		this.promotedBatches = promotedBatches;
	}

	public Integer getFailedBatches() {
		return failedBatches;
	}

	public void setFailedBatches(Integer failedBatches) {
		this.failedBatches = failedBatches;
	}

	public Integer getSkippedBatches() {
		return skippedBatches;
	}

	public void setSkippedBatches(Integer skippedBatches) {
		this.skippedBatches = skippedBatches;
	}

	public Long getTotalRecords() {
		return totalRecords;
	}

	public void setTotalRecords(Long totalRecords) {
		this.totalRecords = totalRecords;
	}

	public Long getPromotedRecords() {
		return promotedRecords;
	}

	public void setPromotedRecords(Long promotedRecords) {
		this.promotedRecords = promotedRecords;
	}

	public Long getFailedRecords() {
		return failedRecords;
	}

	public void setFailedRecords(Long failedRecords) {
		this.failedRecords = failedRecords;
	}

	public Long getSkippedRecords() {
		return skippedRecords;
	}

	public void setSkippedRecords(Long skippedRecords) {
		this.skippedRecords = skippedRecords;
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

	public ExecutionControlState getControlState() {
		return controlState == null ? ExecutionControlState.RUNNING : controlState;
	}

	public void setControlState(ExecutionControlState controlState) {
		this.controlState = controlState;
	}

	public Instant getControlUpdatedAt() {
		return controlUpdatedAt;
	}

	public void setControlUpdatedAt(Instant controlUpdatedAt) {
		this.controlUpdatedAt = controlUpdatedAt;
	}

	public String getLastControlAction() {
		return lastControlAction;
	}

	public void setLastControlAction(String lastControlAction) {
		this.lastControlAction = lastControlAction;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof MigrationPromotion other)) {
			return false;
		}
		return id != null && id.equals(other.id);
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}
}
