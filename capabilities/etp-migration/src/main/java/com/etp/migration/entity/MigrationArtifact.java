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
@Table(name = "migration_artifact")
public class MigrationArtifact {

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
	@Column(name = "artifact_type", nullable = false, length = 50)
	private ArtifactType artifactType;

	@Enumerated(EnumType.STRING)
	@Column(name = "storage_provider", nullable = false, length = 50)
	private StorageProvider storageProvider;

	@Column(name = "object_key", nullable = false, length = 2000)
	private String objectKey;

	@Enumerated(EnumType.STRING)
	@Column(name = "file_format", nullable = false, length = 30)
	private ArtifactFileFormat fileFormat;

	@Column(name = "file_size_bytes")
	private Long fileSizeBytes;

	@Column(name = "record_count")
	private Long recordCount;

	@Column(name = "checksum_algorithm", length = 30)
	private String checksumAlgorithm;

	@Column(name = "checksum", length = 256)
	private String checksum;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	public MigrationArtifact() {
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

	public ArtifactType getArtifactType() {
		return artifactType;
	}

	public void setArtifactType(ArtifactType artifactType) {
		this.artifactType = artifactType;
	}

	public StorageProvider getStorageProvider() {
		return storageProvider;
	}

	public void setStorageProvider(StorageProvider storageProvider) {
		this.storageProvider = storageProvider;
	}

	public String getObjectKey() {
		return objectKey;
	}

	public void setObjectKey(String objectKey) {
		this.objectKey = objectKey;
	}

	public ArtifactFileFormat getFileFormat() {
		return fileFormat;
	}

	public void setFileFormat(ArtifactFileFormat fileFormat) {
		this.fileFormat = fileFormat;
	}

	public Long getFileSizeBytes() {
		return fileSizeBytes;
	}

	public void setFileSizeBytes(Long fileSizeBytes) {
		this.fileSizeBytes = fileSizeBytes;
	}

	public Long getRecordCount() {
		return recordCount;
	}

	public void setRecordCount(Long recordCount) {
		this.recordCount = recordCount;
	}

	public String getChecksumAlgorithm() {
		return checksumAlgorithm;
	}

	public void setChecksumAlgorithm(String checksumAlgorithm) {
		this.checksumAlgorithm = checksumAlgorithm;
	}

	public String getChecksum() {
		return checksum;
	}

	public void setChecksum(String checksum) {
		this.checksum = checksum;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof MigrationArtifact other)) {
			return false;
		}
		return id != null && id.equals(other.id);
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}
}
