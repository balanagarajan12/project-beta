package com.etp.migration.repository;

import com.etp.migration.entity.MigrationError;
import com.etp.migration.entity.MigrationStage;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MigrationErrorRepository extends JpaRepository<MigrationError, Long> {

	Page<MigrationError> findByMigrationRun_Id(UUID migrationRunId, Pageable pageable);

	long countByMigrationRun_Id(UUID migrationRunId);

	@Query("select e from MigrationError e where e.migrationRun.id = :runId")
	Stream<MigrationError> streamByMigrationRunId(@Param("runId") UUID runId);

	boolean existsByMigrationRun_IdAndMigrationBatch_IdAndStageAndErrorCodeAndErrorMessageAndSourceRecordKeyIsNull(
			UUID migrationRunId,
			Long migrationBatchId,
			MigrationStage stage,
			String errorCode,
			String errorMessage);

	boolean existsByMigrationRun_IdAndMigrationBatchIsNullAndStageAndErrorCodeAndErrorMessageAndSourceRecordKeyIsNull(
			UUID migrationRunId,
			MigrationStage stage,
			String errorCode,
			String errorMessage);

	boolean existsByMigrationRun_IdAndMigrationBatch_IdAndSourceRecordKeyAndStageAndErrorCodeAndErrorMessage(
			UUID migrationRunId,
			Long migrationBatchId,
			String sourceRecordKey,
			MigrationStage stage,
			String errorCode,
			String errorMessage);

	boolean existsByMigrationRun_IdAndMigrationBatchIsNullAndSourceRecordKeyAndStageAndErrorCodeAndErrorMessage(
			UUID migrationRunId,
			String sourceRecordKey,
			MigrationStage stage,
			String errorCode,
			String errorMessage);
}
