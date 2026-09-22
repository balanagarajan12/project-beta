package com.etp.migration.repository;

import com.etp.migration.entity.MigrationRecord;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MigrationRecordRepository extends JpaRepository<MigrationRecord, Long> {

	long countByMigrationRun_Id(UUID migrationRunId);
}
