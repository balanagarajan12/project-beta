package com.etp.migration.repository;

import com.etp.migration.entity.MigrationPromotionBatch;
import com.etp.migration.entity.PromotionBatchStatus;
import com.etp.migration.promotion.PromotionBatchHeader;
import com.etp.migration.promotion.PromotionBatchRef;
import com.etp.migration.promotion.PromotionBatchRow;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MigrationPromotionBatchRepository extends JpaRepository<MigrationPromotionBatch, Long> {

	List<MigrationPromotionBatch> findByPromotion_Id(UUID promotionId);

	@Query("""
			select b
			from MigrationPromotionBatch b
			where b.promotion.id = :promotionId
			  and b.status = :status
			order by b.migrationBatch.batchNumber
			""")
	List<MigrationPromotionBatch> findByPromotion_IdAndStatusOrderByMigrationBatch_BatchNumberAsc(
			@Param("promotionId") UUID promotionId,
			@Param("status") PromotionBatchStatus status);

	@Query("""
			select new com.etp.migration.promotion.PromotionBatchRef(
				b.id, b.migrationBatch.id, b.eligibleRecordCount)
			from MigrationPromotionBatch b
			where b.promotion.id = :promotionId
			  and b.status = :status
			order by b.migrationBatch.batchNumber
			""")
	List<PromotionBatchRef> findRefsByPromotionAndStatus(
			@Param("promotionId") UUID promotionId,
			@Param("status") PromotionBatchStatus status);

	@Query("""
			select new com.etp.migration.promotion.PromotionBatchHeader(
				b.migrationBatch.id, b.status, b.eligibleRecordCount, b.skippedRecordCount)
			from MigrationPromotionBatch b
			where b.promotion.id = :promotionId
			""")
	List<PromotionBatchHeader> findHeadersByPromotionId(@Param("promotionId") UUID promotionId);

	@Query("""
			select b.migrationBatch.id
			from MigrationPromotionBatch b
			where b.migrationRun.id = :runId
			  and b.status = :status
			""")
	List<Long> findBatchIdsByRunAndStatus(
			@Param("runId") UUID runId,
			@Param("status") PromotionBatchStatus status);

	List<MigrationPromotionBatch> findByMigrationRun_Id(UUID migrationRunId);

	@Query("""
			select b.status as status, count(b) as batches,
			       coalesce(sum(b.eligibleRecordCount), 0) as eligibleRecords
			from MigrationPromotionBatch b
			where b.promotion.id = :promotionId
			group by b.status
			""")
	List<PromotionBatchStatusCount> summarize(@Param("promotionId") UUID promotionId);

	@Query(value = """
			select new com.etp.migration.promotion.PromotionBatchRow(
				b.migrationBatchId, mb.batchNumber, b.status,
				b.eligibleRecordCount, b.promotedRecordCount, b.failedRecordCount, b.skippedRecordCount,
				b.startedAt, b.completedAt, b.errorCode, b.errorMessage)
			from MigrationPromotionBatch b
			join b.migrationBatch mb
			where b.migrationRun.id = :runId
			order by mb.batchNumber asc
			""",
			countQuery = "select count(b) from MigrationPromotionBatch b where b.migrationRun.id = :runId")
	Page<PromotionBatchRow> pageByRun(@Param("runId") UUID runId, Pageable pageable);

	@Query(value = """
			select new com.etp.migration.promotion.PromotionBatchRow(
				b.migrationBatchId, mb.batchNumber, b.status,
				b.eligibleRecordCount, b.promotedRecordCount, b.failedRecordCount, b.skippedRecordCount,
				b.startedAt, b.completedAt, b.errorCode, b.errorMessage)
			from MigrationPromotionBatch b
			join b.migrationBatch mb
			where b.migrationRun.id = :runId
			  and b.status = :status
			order by mb.batchNumber asc
			""",
			countQuery = """
					select count(b) from MigrationPromotionBatch b
					where b.migrationRun.id = :runId and b.status = :status
					""")
	Page<PromotionBatchRow> pageByRunAndStatus(
			@Param("runId") UUID runId,
			@Param("status") PromotionBatchStatus status,
			Pageable pageable);

	public interface PromotionBatchStatusCount {
		PromotionBatchStatus getStatus();
		long getBatches();
		long getEligibleRecords();
	}
}
