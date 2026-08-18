package com.etp.iam.repository;

import com.etp.iam.entity.PrincipalGroupMapping;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PrincipalGroupMappingRepository extends JpaRepository<PrincipalGroupMapping, UUID> {

	List<PrincipalGroupMapping> findByPrincipal_PrincipalId(UUID principalId);

	@Query("""
			SELECT pgm.group.groupId
			FROM PrincipalGroupMapping pgm
			WHERE pgm.principal.principalId = :principalId
			AND pgm.group.status = 'ACTIVE'
			""")
	List<UUID> findActiveGroupIdsByPrincipalId(@Param("principalId") UUID principalId);
}
