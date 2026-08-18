package com.etp.iam.repository;

import com.etp.iam.entity.GroupTenantAssignment;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupTenantAssignmentRepository extends JpaRepository<GroupTenantAssignment, UUID> {

	List<GroupTenantAssignment> findByGroup_GroupId(UUID groupId);

	@Query("""
			SELECT gta
			FROM GroupTenantAssignment gta
			JOIN FETCH gta.tenant t
			LEFT JOIN FETCH gta.scope s
			WHERE gta.group.groupId IN :groupIds
			AND (t.tenantCode = :tenant OR t.tenantName = :tenant)
			""")
	List<GroupTenantAssignment> findByGroupIdsAndTenantIdentifier(
			@Param("groupIds") List<UUID> groupIds,
			@Param("tenant") String tenant);
}
