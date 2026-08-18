package com.etp.iam.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
		name = "principals",
		uniqueConstraints = @UniqueConstraint(
				name = "uq_principal_issuer_external_id",
				columnNames = {"issuer", "external_id"}
		)
)
public class Principal {

	@Id
	@Column(name = "principal_id", nullable = false, updatable = false)
	@JdbcTypeCode(SqlTypes.UUID)
	private UUID principalId;

	@Enumerated(EnumType.STRING)
	@Column(name = "principal_type", nullable = false, length = 30)
	private PrincipalType principalType;

	@Column(name = "issuer", nullable = false, length = 500)
	private String issuer;

	@Column(name = "external_id", nullable = false, length = 200)
	private String externalId;

	@Column(name = "principal_name", nullable = false, length = 200)
	private String principalName;

	@Column(name = "status", nullable = false, length = 30)
	private String status;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "created_by", length = 100)
	private String createdBy;

	@Column(name = "updated_by", length = 100)
	private String updatedBy;

	protected Principal() {
	}

	public Principal(
			PrincipalType principalType,
			String issuer,
			String externalId,
			String principalName,
			String status) {
		this.principalType = principalType;
		this.issuer = issuer;
		this.externalId = externalId;
		this.principalName = principalName;
		this.status = status;
	}

	@PrePersist
	void onCreate() {
		if (principalId == null) {
			principalId = UUID.randomUUID();
		}
		Instant now = Instant.now();
		if (createdAt == null) {
			createdAt = now;
		}
		if (updatedAt == null) {
			updatedAt = now;
		}
		if (status == null) {
			status = "ACTIVE";
		}
	}

	@PreUpdate
	void onUpdate() {
		updatedAt = Instant.now();
	}

	public UUID getPrincipalId() {
		return principalId;
	}

	public PrincipalType getPrincipalType() {
		return principalType;
	}

	public void setPrincipalType(PrincipalType principalType) {
		this.principalType = principalType;
	}

	public String getIssuer() {
		return issuer;
	}

	public void setIssuer(String issuer) {
		this.issuer = issuer;
	}

	public String getExternalId() {
		return externalId;
	}

	public void setExternalId(String externalId) {
		this.externalId = externalId;
	}

	public String getPrincipalName() {
		return principalName;
	}

	public void setPrincipalName(String principalName) {
		this.principalName = principalName;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public String getCreatedBy() {
		return createdBy;
	}

	public void setCreatedBy(String createdBy) {
		this.createdBy = createdBy;
	}

	public String getUpdatedBy() {
		return updatedBy;
	}

	public void setUpdatedBy(String updatedBy) {
		this.updatedBy = updatedBy;
	}
}
