package com.etp.migration.repository;

import com.etp.migration.entity.MigrationConfigStatus;
import com.etp.migration.entity.MigrationDefinition;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MigrationDefinitionRepository extends JpaRepository<MigrationDefinition, UUID> {

	boolean existsByNameAndVersion(String name, String version);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select d from MigrationDefinition d where d.id = :id")
	Optional<MigrationDefinition> findByIdForUpdate(@Param("id") UUID id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select definition from MigrationDefinition definition
			where definition.name = :name
			  and definition.status = :status
			  and definition.id <> :id
			""")
	List<MigrationDefinition> findOtherActiveByNameForUpdate(
			@Param("name") String name,
			@Param("status") MigrationConfigStatus status,
			@Param("id") UUID id);
}
