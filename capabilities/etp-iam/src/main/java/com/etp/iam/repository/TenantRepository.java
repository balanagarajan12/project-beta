package com.etp.iam.repository;

import com.etp.iam.entity.Tenant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {

	Optional<Tenant> findByCompany_CompanyIdAndTenantCode(UUID companyId, String tenantCode);
}
