package com.etp.migration.repository;

import com.etp.migration.entity.MigrationError;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MigrationErrorRepository extends JpaRepository<MigrationError, Long> {

	long countByMigrationRun_Id(UUID migrationRunId);

	long countByMigrationBatch_Id(Long migrationBatchId);

	@Query("select e from MigrationError e where e.migrationRun.id = :runId")
	Stream<MigrationError> streamByMigrationRunId(@Param("runId") UUID runId);
}
