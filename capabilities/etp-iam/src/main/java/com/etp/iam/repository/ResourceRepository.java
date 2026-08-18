package com.etp.iam.repository;

import com.etp.iam.entity.Resource;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResourceRepository extends JpaRepository<Resource, UUID> {

	List<Resource> findByApplication_ApplicationId(UUID applicationId);
}
