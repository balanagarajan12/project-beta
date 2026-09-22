package com.etp.migration.entity;

import jakarta.persistence.CascadeType;
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
		name = "migration_definition",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_migration_definition_name_version",
				columnNames = {"name", "version"}
		)
)
public class MigrationDefinition {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	@JdbcTypeCode(SqlTypes.UUID)
	private UUID id;

	@Column(name = "name", nullable = false, length = 200)
	private String name;

	@Column(name = "version", nullable = false, length = 50)
	private String version;

	@Column(name = "description", length = 1000)
	private String description;

	@Column(name = "source_type", nullable = false, length = 50)
	private String sourceType;

	@Column(name = "target_type", nullable = false, length = 50)
	private String targetType;

	@Column(name = "target_entity", length = 200)
	private String targetEntity;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "mapping_id")
	private MigrationMapping mapping;

	@Column(name = "batch_size", nullable = false)
	private Integer batchSize = 10000;

	@Enumerated(EnumType.STRING)
	@Column(name = "load_mode", nullable = false, length = 30)
	private LoadMode loadMode = LoadMode.BULK;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 30)
	private MigrationConfigStatus status = MigrationConfigStatus.DRAFT;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "configuration", nullable = false, columnDefinition = "jsonb")
	private Map<String, Object> configuration = new HashMap<>();

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "created_by", length = 100)
	private String createdBy;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "updated_by", length = 100)
	private String updatedBy;

	@OneToMany(mappedBy = "migrationDefinition", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
	private List<MigrationSource> sources = new ArrayList<>();

	@OneToMany(mappedBy = "migrationDefinition", fetch = FetchType.LAZY)
	private List<MigrationRun> runs = new ArrayList<>();

	public MigrationDefinition() {
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

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getVersion() {
		return version;
	}

	public void setVersion(String version) {
		this.version = version;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public String getSourceType() {
		return sourceType;
	}

	public void setSourceType(String sourceType) {
		this.sourceType = sourceType;
	}

	public String getTargetType() {
		return targetType;
	}

	public void setTargetType(String targetType) {
		this.targetType = targetType;
	}

	public String getTargetEntity() {
		return targetEntity;
	}

	public void setTargetEntity(String targetEntity) {
		this.targetEntity = targetEntity;
	}

	public MigrationMapping getMapping() {
		return mapping;
	}

	public void setMapping(MigrationMapping mapping) {
		this.mapping = mapping;
	}

	public Integer getBatchSize() {
		return batchSize;
	}

	public void setBatchSize(Integer batchSize) {
		this.batchSize = batchSize;
	}

	public LoadMode getLoadMode() {
		return loadMode;
	}

	public void setLoadMode(LoadMode loadMode) {
		this.loadMode = loadMode;
	}

	public MigrationConfigStatus getStatus() {
		return status;
	}

	public void setStatus(MigrationConfigStatus status) {
		this.status = status;
	}

	public Map<String, Object> getConfiguration() {
		return configuration;
	}

	public void setConfiguration(Map<String, Object> configuration) {
		this.configuration = configuration;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}

	public String getCreatedBy() {
		return createdBy;
	}

	public void setCreatedBy(String createdBy) {
		this.createdBy = createdBy;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(Instant updatedAt) {
		this.updatedAt = updatedAt;
	}

	public String getUpdatedBy() {
		return updatedBy;
	}

	public void setUpdatedBy(String updatedBy) {
		this.updatedBy = updatedBy;
	}

	public List<MigrationSource> getSources() {
		return sources;
	}

	public List<MigrationRun> getRuns() {
		return runs;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof MigrationDefinition other)) {
			return false;
		}
		return id != null && id.equals(other.id);
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}
}
