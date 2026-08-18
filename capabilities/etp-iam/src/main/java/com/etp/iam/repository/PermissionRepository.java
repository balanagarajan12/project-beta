package com.etp.iam.repository;

import com.etp.iam.entity.Permission;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PermissionRepository extends JpaRepository<Permission, UUID> {

	Optional<Permission> findByPermissionName(String permissionName);
}
