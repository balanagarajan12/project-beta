package com.etp.platform.capability.repository;

import com.etp.platform.capability.CapabilityDependency;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CapabilityDependencyRepository extends JpaRepository<CapabilityDependency, UUID> {

    List<CapabilityDependency> findByCapabilityVersionCapabilityVersionId(UUID capabilityVersionId);
}
