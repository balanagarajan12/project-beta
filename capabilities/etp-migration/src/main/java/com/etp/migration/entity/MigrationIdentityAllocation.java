package com.etp.migration.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
		name = "migration_identity_allocation",
		uniqueConstraints = {
				@UniqueConstraint(
						name = "uk_identity_allocation_source",
						columnNames = {"migration_run_id", "entity_name", "source_identity"}),
				@UniqueConstraint(
						name = "uk_identity_allocation_sequence",
						columnNames = {"migration_run_id", "entity_name", "sequence_no"})
		})
public class MigrationIdentityAllocation {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false, updatable = false)
	private Long id;

	@Column(name = "migration_run_id", nullable = false)
	@JdbcTypeCode(SqlTypes.UUID)
	private UUID migrationRunId;

	@Column(name = "entity_name", nullable = false, length = 200)
	private String entityName;

	@Column(name = "source_identity", nullable = false, length = 500)
	private String sourceIdentity;

	@Column(name = "target_identity", nullable = false, length = 500)
	private String targetIdentity;

	@Column(name = "sequence_no", nullable = false)
	private long sequenceNo;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	public MigrationIdentityAllocation() {
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

	public UUID getMigrationRunId() {
		return migrationRunId;
	}

	public void setMigrationRunId(UUID migrationRunId) {
		this.migrationRunId = migrationRunId;
	}

	public String getEntityName() {
		return entityName;
	}

	public void setEntityName(String entityName) {
		this.entityName = entityName;
	}

	public String getSourceIdentity() {
		return sourceIdentity;
	}

	public void setSourceIdentity(String sourceIdentity) {
		this.sourceIdentity = sourceIdentity;
	}

	public String getTargetIdentity() {
		return targetIdentity;
	}

	public void setTargetIdentity(String targetIdentity) {
		this.targetIdentity = targetIdentity;
	}

	public long getSequenceNo() {
		return sequenceNo;
	}

	public void setSequenceNo(long sequenceNo) {
		this.sequenceNo = sequenceNo;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
