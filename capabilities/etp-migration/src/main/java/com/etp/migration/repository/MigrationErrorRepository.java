package com.etp.migration.repository;

import com.etp.migration.entity.MigrationError;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MigrationErrorRepository extends JpaRepository<MigrationError, Long> {

	long countByMigrationRun_Id(UUID migrationRunId);

	long countByMigrationBatch_Id(Long migrationBatchId);
}
