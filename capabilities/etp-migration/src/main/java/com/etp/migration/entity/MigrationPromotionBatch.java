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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(
		name = "migration_promotion_batch",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_migration_promotion_batch_promotion_batch",
				columnNames = {"promotion_id", "migration_batch_id"}
		)
)
public class MigrationPromotionBatch {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false, updatable = false)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "promotion_id", nullable = false)
	private MigrationPromotion promotion;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "migration_run_id", nullable = false)
	private MigrationRun migrationRun;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "migration_batch_id", nullable = false)
	private MigrationBatch migrationBatch;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 30)
	private PromotionBatchStatus status = PromotionBatchStatus.NOT_ELIGIBLE;

	@Column(name = "eligible_record_count", nullable = false)
	private Long eligibleRecordCount = 0L;

	@Column(name = "promoted_record_count", nullable = false)
	private Long promotedRecordCount = 0L;

	@Column(name = "failed_record_count", nullable = false)
	private Long failedRecordCount = 0L;

	@Column(name = "skipped_record_count", nullable = false)
	private Long skippedRecordCount = 0L;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Column(name = "error_code", length = 100)
	private String errorCode;

	@Column(name = "error_message", length = 2000)
	private String errorMessage;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public MigrationPromotionBatch() {
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

	public MigrationPromotion getPromotion() {
		return promotion;
	}

	public void setPromotion(MigrationPromotion promotion) {
		this.promotion = promotion;
	}

	public MigrationRun getMigrationRun() {
		return migrationRun;
	}

	public void setMigrationRun(MigrationRun migrationRun) {
		this.migrationRun = migrationRun;
	}

	public MigrationBatch getMigrationBatch() {
		return migrationBatch;
	}

	public void setMigrationBatch(MigrationBatch migrationBatch) {
		this.migrationBatch = migrationBatch;
	}

	public PromotionBatchStatus getStatus() {
		return status;
	}

	public void setStatus(PromotionBatchStatus status) {
		this.status = status;
	}

	public Long getEligibleRecordCount() {
		return eligibleRecordCount;
	}

	public void setEligibleRecordCount(Long eligibleRecordCount) {
		this.eligibleRecordCount = eligibleRecordCount;
	}

	public Long getPromotedRecordCount() {
		return promotedRecordCount;
	}

	public void setPromotedRecordCount(Long promotedRecordCount) {
		this.promotedRecordCount = promotedRecordCount;
	}

	public Long getFailedRecordCount() {
		return failedRecordCount;
	}

	public void setFailedRecordCount(Long failedRecordCount) {
		this.failedRecordCount = failedRecordCount;
	}

	public Long getSkippedRecordCount() {
		return skippedRecordCount;
	}

	public void setSkippedRecordCount(Long skippedRecordCount) {
		this.skippedRecordCount = skippedRecordCount;
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
		if (!(o instanceof MigrationPromotionBatch other)) {
			return false;
		}
		return id != null && id.equals(other.id);
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}
}
