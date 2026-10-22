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
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
		name = "migration_run",
		uniqueConstraints = {
				@UniqueConstraint(name = "uk_migration_run_number", columnNames = {"migration_definition_id", "run_number"}),
				@UniqueConstraint(name = "uk_migration_run_idempotency", columnNames = {"migration_definition_id", "idempotency_key"})
		}
)
public class MigrationRun {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	@JdbcTypeCode(SqlTypes.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "migration_definition_id", nullable = false)
	private MigrationDefinition migrationDefinition;

	@Column(name = "run_number", nullable = false)
	private Long runNumber;

	@Column(name = "idempotency_key", length = 300)
	private String idempotencyKey;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 30)
	private MigrationRunStatus status = MigrationRunStatus.CREATED;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Column(name = "requested_by", length = 100)
	private String requestedBy;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "source_snapshot", nullable = false, columnDefinition = "jsonb")
	private Map<String, Object> sourceSnapshot = new HashMap<>();

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "configuration_snapshot", nullable = false, columnDefinition = "jsonb")
	private Map<String, Object> configurationSnapshot = new HashMap<>();

	@Column(name = "total_records", nullable = false)
	private Long totalRecords = 0L;

	@Column(name = "processed_records", nullable = false)
	private Long processedRecords = 0L;

	@Column(name = "success_records", nullable = false)
	private Long successRecords = 0L;

	@Column(name = "duplicate_records", nullable = false)
	private Long duplicateRecords = 0L;

	@Column(name = "validation_failures", nullable = false)
	private Long validationFailures = 0L;

	@Column(name = "enrichment_failures", nullable = false)
	private Long enrichmentFailures = 0L;

	@Column(name = "load_failures", nullable = false)
	private Long loadFailures = 0L;

	@Column(name = "rejected_records", nullable = false)
	private Long rejectedRecords = 0L;

	@Column(name = "total_batches", nullable = false)
	private Integer totalBatches = 0;

	@Column(name = "completed_batches", nullable = false)
	private Integer completedBatches = 0;

	@Column(name = "failed_batches", nullable = false)
	private Integer failedBatches = 0;

	@Column(name = "error_message", length = 2000)
	private String errorMessage;

	@Column(name = "background_execution", nullable = false)
	private boolean backgroundExecution;

	@Enumerated(EnumType.STRING)
	@Column(name = "control_state", nullable = false, length = 20)
	private ExecutionControlState controlState = ExecutionControlState.RUNNING;

	@Column(name = "control_updated_at")
	private Instant controlUpdatedAt;

	@Column(name = "last_control_action", length = 20)
	private String lastControlAction;

	/**
	 * Written only by {@code MigrationExecutionClaim}. A batch flush must not put a stale null back.
	 */
	@Column(name = "execution_worker_id", length = 200, updatable = false)
	private String executionWorkerId;

	@Column(name = "execution_lease_until", updatable = false)
	private Instant executionLeaseUntil;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@OneToMany(mappedBy = "migrationRun", fetch = FetchType.LAZY)
	private List<MigrationBatch> batches = new ArrayList<>();

	@OneToMany(mappedBy = "migrationRun", fetch = FetchType.LAZY)
	private List<MigrationArtifact> artifacts = new ArrayList<>();

	@OneToMany(mappedBy = "migrationRun", fetch = FetchType.LAZY)
	private List<MigrationError> errors = new ArrayList<>();

	@OneToMany(mappedBy = "migrationRun", fetch = FetchType.LAZY)
	private List<MigrationReconciliation> reconciliations = new ArrayList<>();

	@OneToMany(mappedBy = "migrationRun", fetch = FetchType.LAZY)
	private List<MigrationRecord> records = new ArrayList<>();

	public MigrationRun() {
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

	public MigrationDefinition getMigrationDefinition() {
		return migrationDefinition;
	}

	public void setMigrationDefinition(MigrationDefinition migrationDefinition) {
		this.migrationDefinition = migrationDefinition;
	}

	public Long getRunNumber() {
		return runNumber;
	}

	public void setRunNumber(Long runNumber) {
		this.runNumber = runNumber;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public void setIdempotencyKey(String idempotencyKey) {
		this.idempotencyKey = idempotencyKey;
	}

	public MigrationRunStatus getStatus() {
		return status;
	}

	public void setStatus(MigrationRunStatus status) {
		this.status = status;
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

	public String getRequestedBy() {
		return requestedBy;
	}

	public void setRequestedBy(String requestedBy) {
		this.requestedBy = requestedBy;
	}

	public Map<String, Object> getSourceSnapshot() {
		return sourceSnapshot;
	}

	public void setSourceSnapshot(Map<String, Object> sourceSnapshot) {
		this.sourceSnapshot = sourceSnapshot;
	}

	public Map<String, Object> getConfigurationSnapshot() {
		return configurationSnapshot;
	}

	public void setConfigurationSnapshot(Map<String, Object> configurationSnapshot) {
		this.configurationSnapshot = configurationSnapshot;
	}

	public Long getTotalRecords() {
		return totalRecords;
	}

	public void setTotalRecords(Long totalRecords) {
		this.totalRecords = totalRecords;
	}

	public Long getProcessedRecords() {
		return processedRecords;
	}

	public void setProcessedRecords(Long processedRecords) {
		this.processedRecords = processedRecords;
	}

	public Long getSuccessRecords() {
		return successRecords;
	}

	public void setSuccessRecords(Long successRecords) {
		this.successRecords = successRecords;
	}

	public Long getDuplicateRecords() {
		return duplicateRecords;
	}

	public void setDuplicateRecords(Long duplicateRecords) {
		this.duplicateRecords = duplicateRecords;
	}

	public Long getValidationFailures() {
		return validationFailures;
	}

	public void setValidationFailures(Long validationFailures) {
		this.validationFailures = validationFailures;
	}

	public Long getEnrichmentFailures() {
		return enrichmentFailures;
	}

	public void setEnrichmentFailures(Long enrichmentFailures) {
		this.enrichmentFailures = enrichmentFailures;
	}

	public Long getLoadFailures() {
		return loadFailures;
	}

	public void setLoadFailures(Long loadFailures) {
		this.loadFailures = loadFailures;
	}

	public Long getRejectedRecords() {
		return rejectedRecords;
	}

	public void setRejectedRecords(Long rejectedRecords) {
		this.rejectedRecords = rejectedRecords;
	}

	public Integer getTotalBatches() {
		return totalBatches;
	}

	public void setTotalBatches(Integer totalBatches) {
		this.totalBatches = totalBatches;
	}

	public Integer getCompletedBatches() {
		return completedBatches;
	}

	public void setCompletedBatches(Integer completedBatches) {
		this.completedBatches = completedBatches;
	}

	public Integer getFailedBatches() {
		return failedBatches;
	}

	public void setFailedBatches(Integer failedBatches) {
		this.failedBatches = failedBatches;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public void setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
	}

	public boolean isBackgroundExecution() {
		return backgroundExecution;
	}

	public void setBackgroundExecution(boolean backgroundExecution) {
		this.backgroundExecution = backgroundExecution;
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

	public String getExecutionWorkerId() {
		return executionWorkerId;
	}

	public void setExecutionWorkerId(String executionWorkerId) {
		this.executionWorkerId = executionWorkerId;
	}

	public Instant getExecutionLeaseUntil() {
		return executionLeaseUntil;
	}

	public void setExecutionLeaseUntil(Instant executionLeaseUntil) {
		this.executionLeaseUntil = executionLeaseUntil;
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

	public List<MigrationBatch> getBatches() {
		return batches;
	}

	public List<MigrationArtifact> getArtifacts() {
		return artifacts;
	}

	public List<MigrationError> getErrors() {
		return errors;
	}

	public List<MigrationReconciliation> getReconciliations() {
		return reconciliations;
	}

	public List<MigrationRecord> getRecords() {
		return records;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof MigrationRun other)) {
			return false;
		}
		return id != null && id.equals(other.id);
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}
}
