package com.etp.iam.repository;

import com.etp.iam.entity.Application;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationRepository extends JpaRepository<Application, UUID> {

	Optional<Application> findByApplicationName(String applicationName);
}
