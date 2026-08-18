package com.etp.iam.repository;

import com.etp.iam.entity.Role;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, UUID> {

	Optional<Role> findByRoleName(String roleName);
}
