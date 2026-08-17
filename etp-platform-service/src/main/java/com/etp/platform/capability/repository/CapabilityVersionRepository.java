package com.etp.platform.capability.repository;

import com.etp.platform.capability.CapabilityVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CapabilityVersionRepository extends JpaRepository<CapabilityVersion, UUID> {

    List<CapabilityVersion> findByCapabilityCapabilityIdOrderByCreatedAtDesc(UUID capabilityId);

    Optional<CapabilityVersion> findByCapabilityCapabilityIdAndVersion(UUID capabilityId, String version);

    Optional<CapabilityVersion> findFirstByCapabilityCapabilityIdOrderByCreatedAtDesc(UUID capabilityId);

    Optional<CapabilityVersion> findByCapabilityCapabilityIdAndStatus(UUID capabilityId, String status);

    boolean existsByCapabilityCapabilityIdAndVersion(UUID capabilityId, String version);
}
