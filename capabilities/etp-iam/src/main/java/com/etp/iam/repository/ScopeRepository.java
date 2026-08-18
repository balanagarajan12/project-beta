package com.etp.iam.repository;

import com.etp.iam.entity.Scope;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScopeRepository extends JpaRepository<Scope, UUID> {

	Optional<Scope> findByScopeTypeAndScopeValue(String scopeType, String scopeValue);
}
