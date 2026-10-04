package com.etp.migration.repository;

import com.etp.migration.entity.MigrationMappingRule;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MigrationMappingRuleRepository extends JpaRepository<MigrationMappingRule, Long> {

	boolean existsByMapping_IdAndSequenceNo(UUID mappingId, Integer sequenceNo);

	Page<MigrationMappingRule> findByMapping_Id(UUID mappingId, Pageable pageable);

	List<MigrationMappingRule> findByMapping_IdAndEnabledTrueOrderBySequenceNoAsc(UUID mappingId);

	List<MigrationMappingRule> findByMapping_IdOrderBySequenceNoAsc(UUID mappingId);

	List<MigrationMappingRule> findByMapping_IdAndEntityNameAndEnabledTrueOrderBySequenceNoAsc(
			UUID mappingId,
			String entityName);
}
