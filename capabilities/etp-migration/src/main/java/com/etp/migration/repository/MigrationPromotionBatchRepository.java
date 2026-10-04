package com.etp.migration.repository;

import com.etp.migration.entity.MigrationPromotionBatch;
import com.etp.migration.entity.PromotionBatchStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MigrationPromotionBatchRepository extends JpaRepository<MigrationPromotionBatch, Long> {

	List<MigrationPromotionBatch> findByPromotion_Id(UUID promotionId);

	@Query("""
			select b
			from MigrationPromotionBatch b
			join fetch b.migrationBatch mb
			where b.promotion.id = :promotionId
			  and b.status = :status
			order by mb.batchNumber
			""")
	List<MigrationPromotionBatch> findByPromotion_IdAndStatusOrderByMigrationBatch_BatchNumberAsc(
			@Param("promotionId") UUID promotionId,
			@Param("status") PromotionBatchStatus status);

	List<MigrationPromotionBatch> findByMigrationRun_Id(UUID migrationRunId);
}
