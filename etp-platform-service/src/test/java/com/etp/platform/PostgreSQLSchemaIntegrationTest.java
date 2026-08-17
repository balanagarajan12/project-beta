package com.etp.platform;

import com.etp.platform.application.Application;
import com.etp.platform.application.ApplicationCapability;
import com.etp.platform.application.ApplicationDefinition;
import com.etp.platform.application.Customer;
import com.etp.platform.capability.Capability;
import com.etp.platform.capability.CapabilityVersion;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PostgreSQLSchemaIntegrationTest extends AbstractPostgreSqlIntegrationTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private EntityManager entityManager;

    @Test
    void flywayMigrationsExecuteSuccessfully() {
        assertThat(flyway.info().applied().length).isEqualTo(13);
    }

    @Test
    @Transactional
    void resolvesCustomerApplicationCapabilityUsageThroughRelationalJoins() {
        Instant now = Instant.now();

        Capability capability = new Capability();
        capability.setCapabilityId(UUID.randomUUID());
        capability.setCapabilityCode("etp.iam");
        capability.setCapabilityName("ETP IAM");
        capability.setLifecycleStatus("ACTIVE");
        capability.setCreatedAt(now);
        capability.setCreatedBy("test");
        capability.setUpdatedAt(now);
        capability.setUpdatedBy("test");
        entityManager.persist(capability);

        CapabilityVersion capabilityVersion = new CapabilityVersion();
        capabilityVersion.setCapabilityVersionId(UUID.randomUUID());
        capabilityVersion.setCapability(capability);
        capabilityVersion.setVersion("1.2.0");
        capabilityVersion.setStatus("PUBLISHED");
        capabilityVersion.setCreatedAt(now);
        capabilityVersion.setCreatedBy("test");
        entityManager.persist(capabilityVersion);

        Customer customer = new Customer();
        customer.setCustomerId(UUID.randomUUID());
        customer.setCustomerCode("customer-a");
        customer.setCustomerName("Customer A");
        customer.setStatus("ACTIVE");
        customer.setCreatedAt(now);
        customer.setCreatedBy("test");
        customer.setUpdatedAt(now);
        customer.setUpdatedBy("test");
        entityManager.persist(customer);

        Application application = new Application();
        application.setApplicationId(UUID.randomUUID());
        application.setCustomer(customer);
        application.setApplicationCode("application-x");
        application.setApplicationName("Application X");
        application.setLifecycleStatus("ACTIVE");
        application.setCreatedAt(now);
        application.setCreatedBy("test");
        application.setUpdatedAt(now);
        application.setUpdatedBy("test");
        entityManager.persist(application);

        ApplicationDefinition applicationDefinition = new ApplicationDefinition();
        applicationDefinition.setApplicationDefinitionId(UUID.randomUUID());
        applicationDefinition.setApplication(application);
        applicationDefinition.setVersion("1.0");
        applicationDefinition.setStatus("APPROVED");
        applicationDefinition.setCreatedAt(now);
        applicationDefinition.setCreatedBy("test");
        entityManager.persist(applicationDefinition);

        ApplicationCapability applicationCapability = new ApplicationCapability();
        applicationCapability.setApplicationCapabilityId(UUID.randomUUID());
        applicationCapability.setApplicationDefinition(applicationDefinition);
        applicationCapability.setCapabilityVersion(capabilityVersion);
        applicationCapability.setUsageType("REQUIRED");
        applicationCapability.setStatus("ACTIVE");
        applicationCapability.setCreatedAt(now);
        applicationCapability.setCreatedBy("test");
        entityManager.persist(applicationCapability);

        entityManager.flush();
        entityManager.clear();

        @SuppressWarnings("unchecked")
        List<Object[]> results = entityManager.createQuery(
                "SELECT c.customerName, a.applicationName, cap.capabilityCode, cv.version "
                        + "FROM ApplicationCapability ac "
                        + "JOIN ac.applicationDefinition ad "
                        + "JOIN ad.application a "
                        + "JOIN a.customer c "
                        + "JOIN ac.capabilityVersion cv "
                        + "JOIN cv.capability cap "
                        + "WHERE cap.capabilityCode = :capabilityCode AND cv.version = :capabilityVersion")
                .setParameter("capabilityCode", "etp.iam")
                .setParameter("capabilityVersion", "1.2.0")
                .getResultList();

        assertThat(results).hasSize(1);
        assertThat(results.get(0)[0]).isEqualTo("Customer A");
        assertThat(results.get(0)[1]).isEqualTo("Application X");
        assertThat(results.get(0)[2]).isEqualTo("etp.iam");
        assertThat(results.get(0)[3]).isEqualTo("1.2.0");
    }
}
