package com.etp.platform.capability.repository;

import com.etp.platform.capability.Capability;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CapabilityRepository extends JpaRepository<Capability, UUID> {

    Optional<Capability> findByCapabilityCode(String capabilityCode);

    boolean existsByCapabilityCode(String capabilityCode);

    @Query("SELECT c FROM Capability c WHERE "
            + "(:category IS NULL OR c.category = :category) AND "
            + "(:status IS NULL OR c.lifecycleStatus = :status)")
    List<Capability> findByOptionalFilters(@Param("category") String category, @Param("status") String status);
}
