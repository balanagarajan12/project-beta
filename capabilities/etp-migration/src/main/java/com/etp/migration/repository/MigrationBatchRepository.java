package com.etp.migration.repository;

import com.etp.migration.entity.MigrationBatch;
import com.etp.migration.entity.MigrationBatchStatus;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MigrationBatchRepository extends JpaRepository<MigrationBatch, Long> {

	Page<MigrationBatch> findByMigrationRun_Id(UUID migrationRunId, Pageable pageable);

	List<MigrationBatch> findByMigrationRun_IdOrderByBatchNumberAsc(UUID migrationRunId);

	List<MigrationBatch> findByMigrationRun_IdAndStatusIn(
			UUID migrationRunId,
			Collection<MigrationBatchStatus> statuses);

	long countByMigrationRun_IdAndStatusIn(UUID migrationRunId, Collection<MigrationBatchStatus> statuses);

	boolean existsByMigrationRun_Id(UUID migrationRunId);

	long countByMigrationRun_Id(UUID migrationRunId);
}
