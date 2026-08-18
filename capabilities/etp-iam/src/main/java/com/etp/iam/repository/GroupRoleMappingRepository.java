package com.etp.iam.repository;

import com.etp.iam.entity.GroupRoleMapping;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupRoleMappingRepository extends JpaRepository<GroupRoleMapping, UUID> {

	List<GroupRoleMapping> findByGroup_GroupId(UUID groupId);

	@Query("""
			SELECT grm.role.roleId
			FROM GroupRoleMapping grm
			WHERE grm.group.groupId IN :groupIds
			AND grm.role.status = 'ACTIVE'
			""")
	List<UUID> findActiveRoleIdsByGroupIds(@Param("groupIds") List<UUID> groupIds);
}
