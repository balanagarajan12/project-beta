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
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "migration_error")
public class MigrationError {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false, updatable = false)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "migration_run_id", nullable = false)
	private MigrationRun migrationRun;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "migration_batch_id")
	private MigrationBatch migrationBatch;

	@Enumerated(EnumType.STRING)
	@Column(name = "stage", nullable = false, length = 50)
	private MigrationStage stage;

	@Column(name = "error_code", length = 100)
	private String errorCode;

	@Column(name = "error_type", length = 100)
	private String errorType;

	@Column(name = "source_record_key", length = 500)
	private String sourceRecordKey;

	@Column(name = "source_record_hash", length = 128)
	private String sourceRecordHash;

	@Column(name = "target_entity_key", length = 500)
	private String targetEntityKey;

	@Column(name = "field_name", length = 500)
	private String fieldName;

	@Column(name = "error_message", nullable = false, length = 2000)
	private String errorMessage;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "error_details", nullable = false, columnDefinition = "jsonb")
	private Map<String, Object> errorDetails = new HashMap<>();

	@Column(name = "retryable", nullable = false)
	private boolean retryable;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	public MigrationError() {
	}

	@PrePersist
	void onCreate() {
		if (createdAt == null) {
			createdAt = Instant.now();
		}
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

	public MigrationBatch getMigrationBatch() {
		return migrationBatch;
	}

	public void setMigrationBatch(MigrationBatch migrationBatch) {
		this.migrationBatch = migrationBatch;
	}

	public MigrationStage getStage() {
		return stage;
	}

	public void setStage(MigrationStage stage) {
		this.stage = stage;
	}

	public String getErrorCode() {
		return errorCode;
	}

	public void setErrorCode(String errorCode) {
		this.errorCode = errorCode;
	}

	public String getErrorType() {
		return errorType;
	}

	public void setErrorType(String errorType) {
		this.errorType = errorType;
	}

	public String getSourceRecordKey() {
		return sourceRecordKey;
	}

	public void setSourceRecordKey(String sourceRecordKey) {
		this.sourceRecordKey = sourceRecordKey;
	}

	public String getSourceRecordHash() {
		return sourceRecordHash;
	}

	public void setSourceRecordHash(String sourceRecordHash) {
		this.sourceRecordHash = sourceRecordHash;
	}

	public String getTargetEntityKey() {
		return targetEntityKey;
	}

	public void setTargetEntityKey(String targetEntityKey) {
		this.targetEntityKey = targetEntityKey;
	}

	public String getFieldName() {
		return fieldName;
	}

	public void setFieldName(String fieldName) {
		this.fieldName = fieldName;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public void setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
	}

	public Map<String, Object> getErrorDetails() {
		return errorDetails;
	}

	public void setErrorDetails(Map<String, Object> errorDetails) {
		this.errorDetails = errorDetails;
	}

	public boolean isRetryable() {
		return retryable;
	}

	public void setRetryable(boolean retryable) {
		this.retryable = retryable;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof MigrationError other)) {
			return false;
		}
		return id != null && id.equals(other.id);
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}
}
