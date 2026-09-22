package com.etp.migration.repository;

import com.etp.migration.entity.MigrationSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MigrationSourceRepository extends JpaRepository<MigrationSource, UUID> {

	Page<MigrationSource> findByMigrationDefinition_Id(UUID migrationDefinitionId, Pageable pageable);

	Optional<MigrationSource> findByIdAndMigrationDefinition_Id(UUID id, UUID migrationDefinitionId);

	List<MigrationSource> findByMigrationDefinition_IdAndEnabledTrueOrderByCreatedAtAsc(UUID migrationDefinitionId);
}
