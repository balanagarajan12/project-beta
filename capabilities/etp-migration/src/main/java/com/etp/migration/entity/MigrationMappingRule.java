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
import java.util.HashMap;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
		name = "migration_mapping_rule",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_mapping_rule_sequence",
				columnNames = {"mapping_id", "sequence_no"}
		)
)
public class MigrationMappingRule {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false, updatable = false)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "mapping_id", nullable = false)
	private MigrationMapping mapping;

	@Column(name = "sequence_no", nullable = false)
	private Integer sequenceNo;

	@Column(name = "source_field", length = 500)
	private String sourceField;

	@Column(name = "target_field", nullable = false, length = 500)
	private String targetField;

	@Enumerated(EnumType.STRING)
	@Column(name = "transformation_type", nullable = false, length = 50)
	private TransformationType transformationType;

	@Column(name = "transformation_expression", columnDefinition = "TEXT")
	private String transformationExpression;

	@Column(name = "default_value", columnDefinition = "TEXT")
	private String defaultValue;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "configuration", nullable = false, columnDefinition = "jsonb")
	private Map<String, Object> configuration = new HashMap<>();

	@Column(name = "mandatory", nullable = false)
	private boolean mandatory;

	@Column(name = "enabled", nullable = false)
	private boolean enabled = true;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public MigrationMappingRule() {
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

	public MigrationMapping getMapping() {
		return mapping;
	}

	public void setMapping(MigrationMapping mapping) {
		this.mapping = mapping;
	}

	public Integer getSequenceNo() {
		return sequenceNo;
	}

	public void setSequenceNo(Integer sequenceNo) {
		this.sequenceNo = sequenceNo;
	}

	public String getSourceField() {
		return sourceField;
	}

	public void setSourceField(String sourceField) {
		this.sourceField = sourceField;
	}

	public String getTargetField() {
		return targetField;
	}

	public void setTargetField(String targetField) {
		this.targetField = targetField;
	}

	public TransformationType getTransformationType() {
		return transformationType;
	}

	public void setTransformationType(TransformationType transformationType) {
		this.transformationType = transformationType;
	}

	public String getTransformationExpression() {
		return transformationExpression;
	}

	public void setTransformationExpression(String transformationExpression) {
		this.transformationExpression = transformationExpression;
	}

	public String getDefaultValue() {
		return defaultValue;
	}

	public void setDefaultValue(String defaultValue) {
		this.defaultValue = defaultValue;
	}

	public Map<String, Object> getConfiguration() {
		return configuration;
	}

	public void setConfiguration(Map<String, Object> configuration) {
		this.configuration = configuration;
	}

	public boolean isMandatory() {
		return mandatory;
	}

	public void setMandatory(boolean mandatory) {
		this.mandatory = mandatory;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
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

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof MigrationMappingRule other)) {
			return false;
		}
		return id != null && id.equals(other.id);
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}
}
