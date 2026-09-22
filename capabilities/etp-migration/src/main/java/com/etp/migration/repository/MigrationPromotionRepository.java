package com.etp.migration.repository;

import com.etp.migration.entity.MigrationPromotion;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MigrationPromotionRepository extends JpaRepository<MigrationPromotion, UUID> {

	List<MigrationPromotion> findByMigrationRun_Id(UUID migrationRunId);

	List<MigrationPromotion> findByMigrationRun_IdOrderByCreatedAtDesc(UUID migrationRunId);
}
