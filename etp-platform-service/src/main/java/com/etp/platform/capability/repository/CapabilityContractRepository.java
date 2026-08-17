package com.etp.platform.capability.repository;

import com.etp.platform.capability.CapabilityContract;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CapabilityContractRepository extends JpaRepository<CapabilityContract, UUID> {

    List<CapabilityContract> findByCapabilityVersionCapabilityVersionId(UUID capabilityVersionId);
}
