package com.etp.migration.repository;

import com.etp.migration.entity.MigrationRun;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MigrationRunRepository extends JpaRepository<MigrationRun, UUID> {

	Page<MigrationRun> findByMigrationDefinition_Id(UUID migrationDefinitionId, Pageable pageable);

	Optional<MigrationRun> findByMigrationDefinition_IdAndIdempotencyKey(
			UUID migrationDefinitionId,
			String idempotencyKey);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from MigrationRun r join fetch r.migrationDefinition where r.id = :id")
	Optional<MigrationRun> findByIdForUpdate(@Param("id") UUID id);

	@Query("""
			select coalesce(max(r.runNumber), 0)
			from MigrationRun r
			where r.migrationDefinition.id = :definitionId
			""")
	long findMaxRunNumber(@Param("definitionId") UUID definitionId);
}
