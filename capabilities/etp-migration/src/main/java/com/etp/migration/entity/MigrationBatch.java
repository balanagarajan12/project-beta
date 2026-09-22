package com.etp.migration.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
		name = "migration_batch",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_migration_batch_run_number",
				columnNames = {"migration_run_id", "batch_number"}
		)
)
public class MigrationBatch {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false, updatable = false)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "migration_run_id", nullable = false)
	private MigrationRun migrationRun;

	@Column(name = "batch_number", nullable = false)
	private Integer batchNumber;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 30)
	private MigrationBatchStatus status = MigrationBatchStatus.PENDING;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "source_locator", nullable = false, columnDefinition = "jsonb")
	private Map<String, Object> sourceLocator = new HashMap<>();

	@Column(name = "source_checksum", length = 128)
	private String sourceChecksum;

	@Column(name = "record_count", nullable = false)
	private Integer recordCount = 0;

	@Column(name = "processed_count", nullable = false)
	private Integer processedCount = 0;

	@Column(name = "success_count", nullable = false)
	private Integer successCount = 0;

	@Column(name = "duplicate_count", nullable = false)
	private Integer duplicateCount = 0;

	@Column(name = "validation_failure_count", nullable = false)
	private Integer validationFailureCount = 0;

	@Column(name = "enrichment_failure_count", nullable = false)
	private Integer enrichmentFailureCount = 0;

	@Column(name = "load_failure_count", nullable = false)
	private Integer loadFailureCount = 0;

	@Column(name = "rejected_count", nullable = false)
	private Integer rejectedCount = 0;

	@Column(name = "attempt_count", nullable = false)
	private Integer attemptCount = 0;

	@Column(name = "worker_id", length = 200)
	private String workerId;

	@Column(name = "claimed_at")
	private Instant claimedAt;

	@Column(name = "lease_until")
	private Instant leaseUntil;

	@Column(name = "next_attempt_at")
	private Instant nextAttemptAt;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Column(name = "error_message", length = 2000)
	private String errorMessage;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@OneToMany(mappedBy = "migrationBatch", fetch = FetchType.LAZY)
	private List<MigrationArtifact> artifacts = new ArrayList<>();

	@OneToMany(mappedBy = "migrationBatch", fetch = FetchType.LAZY)
	private List<MigrationError> errors = new ArrayList<>();

	@OneToMany(mappedBy = "migrationBatch", fetch = FetchType.LAZY)
	private List<MigrationRecord> records = new ArrayList<>();

	public MigrationBatch() {
	}

	@PrePersist
	void onCreate() {
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

	public Long getId() {
		return id;
	}

	public MigrationRun getMigrationRun() {
		return migrationRun;
	}

	public void setMigrationRun(MigrationRun migrationRun) {
		this.migrationRun = migrationRun;
	}

	public Integer getBatchNumber() {
		return batchNumber;
	}

	public void setBatchNumber(Integer batchNumber) {
		this.batchNumber = batchNumber;
	}

	public MigrationBatchStatus getStatus() {
		return status;
	}

	public void setStatus(MigrationBatchStatus status) {
		this.status = status;
	}

	public Map<String, Object> getSourceLocator() {
		return sourceLocator;
	}

	public void setSourceLocator(Map<String, Object> sourceLocator) {
		this.sourceLocator = sourceLocator;
	}

	public String getSourceChecksum() {
		return sourceChecksum;
	}

	public void setSourceChecksum(String sourceChecksum) {
		this.sourceChecksum = sourceChecksum;
	}

	public Integer getRecordCount() {
		return recordCount;
	}

	public void setRecordCount(Integer recordCount) {
		this.recordCount = recordCount;
	}

	public Integer getProcessedCount() {
		return processedCount;
	}

	public void setProcessedCount(Integer processedCount) {
		this.processedCount = processedCount;
	}

	public Integer getSuccessCount() {
		return successCount;
	}

	public void setSuccessCount(Integer successCount) {
		this.successCount = successCount;
	}

	public Integer getDuplicateCount() {
		return duplicateCount;
	}

	public void setDuplicateCount(Integer duplicateCount) {
		this.duplicateCount = duplicateCount;
	}

	public Integer getValidationFailureCount() {
		return validationFailureCount;
	}

	public void setValidationFailureCount(Integer validationFailureCount) {
		this.validationFailureCount = validationFailureCount;
	}

	public Integer getEnrichmentFailureCount() {
		return enrichmentFailureCount;
	}

	public void setEnrichmentFailureCount(Integer enrichmentFailureCount) {
		this.enrichmentFailureCount = enrichmentFailureCount;
	}

	public Integer getLoadFailureCount() {
		return loadFailureCount;
	}

	public void setLoadFailureCount(Integer loadFailureCount) {
		this.loadFailureCount = loadFailureCount;
	}

	public Integer getRejectedCount() {
		return rejectedCount;
	}

	public void setRejectedCount(Integer rejectedCount) {
		this.rejectedCount = rejectedCount;
	}

	public Integer getAttemptCount() {
		return attemptCount;
	}

	public void setAttemptCount(Integer attemptCount) {
		this.attemptCount = attemptCount;
	}

	public String getWorkerId() {
		return workerId;
	}

	public void setWorkerId(String workerId) {
		this.workerId = workerId;
	}

	public Instant getClaimedAt() {
		return claimedAt;
	}

	public void setClaimedAt(Instant claimedAt) {
		this.claimedAt = claimedAt;
	}

	public Instant getLeaseUntil() {
		return leaseUntil;
	}

	public void setLeaseUntil(Instant leaseUntil) {
		this.leaseUntil = leaseUntil;
	}

	public Instant getNextAttemptAt() {
		return nextAttemptAt;
	}

	public void setNextAttemptAt(Instant nextAttemptAt) {
		this.nextAttemptAt = nextAttemptAt;
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

	public String getErrorMessage() {
		return errorMessage;
	}

	public void setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(Instant updatedAt) {
		this.updatedAt = updatedAt;
	}

	public List<MigrationArtifact> getArtifacts() {
		return artifacts;
	}

	public List<MigrationError> getErrors() {
		return errors;
	}

	public List<MigrationRecord> getRecords() {
		return records;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof MigrationBatch other)) {
			return false;
		}
		return id != null && id.equals(other.id);
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}
}
