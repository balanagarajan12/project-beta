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

@Entity
@Table(name = "migration_record")
public class MigrationRecord {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false, updatable = false)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "migration_run_id", nullable = false)
	private MigrationRun migrationRun;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "migration_batch_id", nullable = false)
	private MigrationBatch migrationBatch;

	@Column(name = "source_record_key", length = 500)
	private String sourceRecordKey;

	@Column(name = "source_record_hash", length = 128)
	private String sourceRecordHash;

	@Column(name = "target_entity_key", length = 500)
	private String targetEntityKey;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 30)
	private MigrationRecordStatus status;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "error_id")
	private MigrationError error;

	@Column(name = "processed_at")
	private Instant processedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected MigrationRecord() {
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

	public MigrationRecordStatus getStatus() {
		return status;
	}

	public void setStatus(MigrationRecordStatus status) {
		this.status = status;
	}

	public MigrationError getError() {
		return error;
	}

	public void setError(MigrationError error) {
		this.error = error;
	}

	public Instant getProcessedAt() {
		return processedAt;
	}

	public void setProcessedAt(Instant processedAt) {
		this.processedAt = processedAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof MigrationRecord other)) {
			return false;
		}
		return id != null && id.equals(other.id);
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}
}
