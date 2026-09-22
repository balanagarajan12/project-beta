package com.etp.migration.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
		name = "migration_reconciliation",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_reconciliation_run_type",
				columnNames = {"migration_run_id", "reconciliation_type"}
		)
)
public class MigrationReconciliation {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	@JdbcTypeCode(SqlTypes.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "migration_run_id", nullable = false)
	private MigrationRun migrationRun;

	@Enumerated(EnumType.STRING)
	@Column(name = "reconciliation_type", nullable = false, length = 50)
	private ReconciliationType reconciliationType;

	@Column(name = "source_count", nullable = false)
	private Long sourceCount = 0L;

	@Column(name = "processed_count", nullable = false)
	private Long processedCount = 0L;

	@Column(name = "success_count", nullable = false)
	private Long successCount = 0L;

	@Column(name = "rejected_count", nullable = false)
	private Long rejectedCount = 0L;

	@Column(name = "duplicate_count", nullable = false)
	private Long duplicateCount = 0L;

	@Column(name = "failure_count", nullable = false)
	private Long failureCount = 0L;

	@Column(name = "difference_count", nullable = false)
	private Long differenceCount = 0L;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 30)
	private ReconciliationStatus status = ReconciliationStatus.PENDING;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "details", nullable = false, columnDefinition = "jsonb")
	private Map<String, Object> details = new HashMap<>();

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	public MigrationReconciliation() {
	}

	@PrePersist
	void onCreate() {
		if (id == null) {
			id = UUID.randomUUID();
		}
		if (createdAt == null) {
			createdAt = Instant.now();
		}
	}

	public UUID getId() {
		return id;
	}

	public MigrationRun getMigrationRun() {
		return migrationRun;
	}

	public void setMigrationRun(MigrationRun migrationRun) {
		this.migrationRun = migrationRun;
	}

	public ReconciliationType getReconciliationType() {
		return reconciliationType;
	}

	public void setReconciliationType(ReconciliationType reconciliationType) {
		this.reconciliationType = reconciliationType;
	}

	public Long getSourceCount() {
		return sourceCount;
	}

	public void setSourceCount(Long sourceCount) {
		this.sourceCount = sourceCount;
	}

	public Long getProcessedCount() {
		return processedCount;
	}

	public void setProcessedCount(Long processedCount) {
		this.processedCount = processedCount;
	}

	public Long getSuccessCount() {
		return successCount;
	}

	public void setSuccessCount(Long successCount) {
		this.successCount = successCount;
	}

	public Long getRejectedCount() {
		return rejectedCount;
	}

	public void setRejectedCount(Long rejectedCount) {
		this.rejectedCount = rejectedCount;
	}

	public Long getDuplicateCount() {
		return duplicateCount;
	}

	public void setDuplicateCount(Long duplicateCount) {
		this.duplicateCount = duplicateCount;
	}

	public Long getFailureCount() {
		return failureCount;
	}

	public void setFailureCount(Long failureCount) {
		this.failureCount = failureCount;
	}

	public Long getDifferenceCount() {
		return differenceCount;
	}

	public void setDifferenceCount(Long differenceCount) {
		this.differenceCount = differenceCount;
	}

	public ReconciliationStatus getStatus() {
		return status;
	}

	public void setStatus(ReconciliationStatus status) {
		this.status = status;
	}

	public Map<String, Object> getDetails() {
		return details;
	}

	public void setDetails(Map<String, Object> details) {
		this.details = details;
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

	public Instant getCreatedAt() {
		return createdAt;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof MigrationReconciliation other)) {
			return false;
		}
		return id != null && id.equals(other.id);
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}
}
