package com.etp.iam.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
		name = "principal_group_mappings",
		uniqueConstraints = @UniqueConstraint(
				name = "uq_principal_group",
				columnNames = {"principal_id", "group_id"}
		)
)
public class PrincipalGroupMapping {

	@Id
	@Column(name = "mapping_id", nullable = false, updatable = false)
	@JdbcTypeCode(SqlTypes.UUID)
	private UUID mappingId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "principal_id", nullable = false)
	private Principal principal;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "group_id", nullable = false)
	private Group group;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "created_by", length = 100)
	private String createdBy;

	protected PrincipalGroupMapping() {
	}

	@PrePersist
	void onCreate() {
		if (mappingId == null) {
			mappingId = UUID.randomUUID();
		}
		if (createdAt == null) {
			createdAt = Instant.now();
		}
	}

	public UUID getMappingId() {
		return mappingId;
	}

	public Principal getPrincipal() {
		return principal;
	}

	public void setPrincipal(Principal principal) {
		this.principal = principal;
	}

	public Group getGroup() {
		return group;
	}

	public void setGroup(Group group) {
		this.group = group;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public String getCreatedBy() {
		return createdBy;
	}

	public void setCreatedBy(String createdBy) {
		this.createdBy = createdBy;
	}
}
