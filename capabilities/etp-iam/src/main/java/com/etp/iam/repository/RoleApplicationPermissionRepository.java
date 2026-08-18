package com.etp.iam.repository;

import com.etp.iam.entity.RoleApplicationPermission;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoleApplicationPermissionRepository extends JpaRepository<RoleApplicationPermission, UUID> {

	List<RoleApplicationPermission> findByRole_RoleId(UUID roleId);

	@Query("""
			SELECT COUNT(rap) > 0
			FROM RoleApplicationPermission rap
			JOIN rap.application app
			JOIN rap.permission perm
			JOIN perm.resource res
			WHERE rap.role.roleId IN :roleIds
			AND app.status = 'ACTIVE'
			AND (app.applicationCode = :application OR app.applicationName = :application)
			AND perm.status = 'ACTIVE'
			AND (perm.permissionCode = :permission OR perm.permissionName = :permission)
			AND res.status = 'ACTIVE'
			""")
	boolean existsActivePermissionForRoles(
			@Param("roleIds") List<UUID> roleIds,
			@Param("application") String application,
			@Param("permission") String permission);
}
