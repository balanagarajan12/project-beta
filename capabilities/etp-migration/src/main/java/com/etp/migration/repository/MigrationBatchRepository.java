package com.etp.migration.repository;

import com.etp.migration.batch.MigrationBatchPageRow;
import com.etp.migration.entity.MigrationBatch;
import com.etp.migration.entity.MigrationBatchStatus;
import com.etp.migration.promotion.BatchHeader;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MigrationBatchRepository extends JpaRepository<MigrationBatch, Long> {

	Page<MigrationBatch> findByMigrationRun_Id(UUID migrationRunId, Pageable pageable);

	@Query(value = """
			select new com.etp.migration.batch.MigrationBatchPageRow(
				b.id, r.id, b.batchNumber, b.status, b.recordCount, b.processedCount, b.successCount,
				b.duplicateCount, b.validationFailureCount, b.enrichmentFailureCount, b.loadFailureCount,
				b.rejectedCount, b.attemptCount, b.workerId, b.claimedAt, b.leaseUntil, b.startedAt,
				b.completedAt, b.errorMessage)
			from MigrationBatch b
			join b.migrationRun r
			where r.id = :runId
			""",
			countQuery = "select count(b) from MigrationBatch b where b.migrationRun.id = :runId")
	Page<MigrationBatchPageRow> pageByRun(@Param("runId") UUID runId, Pageable pageable);

	List<MigrationBatch> findByMigrationRun_IdOrderByBatchNumberAsc(UUID migrationRunId);

	@Query("""
			select new com.etp.migration.promotion.BatchHeader(b.id, b.batchNumber, b.status)
			from MigrationBatch b
			where b.migrationRun.id = :runId
			order by b.batchNumber asc
			""")
	List<BatchHeader> findHeadersByMigrationRunId(@Param("runId") UUID runId);

	List<MigrationBatch> findByMigrationRun_IdAndStatusIn(
			UUID migrationRunId,
			Collection<MigrationBatchStatus> statuses);

	long countByMigrationRun_IdAndStatusIn(UUID migrationRunId, Collection<MigrationBatchStatus> statuses);

	boolean existsByMigrationRun_Id(UUID migrationRunId);

	long countByMigrationRun_Id(UUID migrationRunId);
}
