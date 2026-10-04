package com.etp.migration.repository;

import com.etp.migration.entity.MigrationIdentityAllocation;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MigrationIdentityAllocationRepository extends JpaRepository<MigrationIdentityAllocation, Long> {

	List<MigrationIdentityAllocation> findByMigrationRunIdAndEntityNameAndSourceIdentityIn(
			UUID migrationRunId,
			String entityName,
			Collection<String> sourceIdentities);
}
