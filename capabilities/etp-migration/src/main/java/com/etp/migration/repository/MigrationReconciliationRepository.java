package com.etp.migration.repository;

import com.etp.migration.entity.MigrationReconciliation;
import com.etp.migration.entity.ReconciliationType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MigrationReconciliationRepository extends JpaRepository<MigrationReconciliation, UUID> {

	Optional<MigrationReconciliation> findByMigrationRun_IdAndReconciliationType(
			UUID migrationRunId,
			ReconciliationType reconciliationType);

	List<MigrationReconciliation> findByMigrationRun_Id(UUID migrationRunId);

	long countByMigrationRun_IdAndReconciliationType(UUID migrationRunId, ReconciliationType reconciliationType);
}
