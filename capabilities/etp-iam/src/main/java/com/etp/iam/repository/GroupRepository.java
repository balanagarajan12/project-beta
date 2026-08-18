package com.etp.iam.repository;

import com.etp.iam.entity.Group;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GroupRepository extends JpaRepository<Group, UUID> {

	Optional<Group> findByGroupName(String groupName);
}
