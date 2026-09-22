package com.etp.migration.repository;

import com.etp.migration.entity.ArtifactType;
import com.etp.migration.entity.MigrationArtifact;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MigrationArtifactRepository extends JpaRepository<MigrationArtifact, Long> {

	boolean existsByMigrationRun_IdAndArtifactType(UUID migrationRunId, ArtifactType artifactType);

	long countByMigrationRun_IdAndArtifactType(UUID migrationRunId, ArtifactType artifactType);
}
