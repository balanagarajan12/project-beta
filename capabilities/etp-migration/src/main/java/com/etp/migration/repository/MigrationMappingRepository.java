package com.etp.migration.repository;

import com.etp.migration.entity.MigrationMapping;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MigrationMappingRepository extends JpaRepository<MigrationMapping, UUID> {

	boolean existsByNameAndVersion(String name, String version);
}
