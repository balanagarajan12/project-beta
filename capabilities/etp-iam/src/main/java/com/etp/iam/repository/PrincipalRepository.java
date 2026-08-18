package com.etp.iam.repository;

import com.etp.iam.entity.Principal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PrincipalRepository extends JpaRepository<Principal, UUID> {

	Optional<Principal> findByIssuerAndExternalId(String issuer, String externalId);
}
