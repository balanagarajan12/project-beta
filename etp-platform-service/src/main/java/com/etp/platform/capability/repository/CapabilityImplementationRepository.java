package com.etp.platform.capability.repository;

import com.etp.platform.capability.CapabilityImplementation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CapabilityImplementationRepository extends JpaRepository<CapabilityImplementation, UUID> {

    List<CapabilityImplementation> findByCapabilityVersionCapabilityVersionId(UUID capabilityVersionId);
}
