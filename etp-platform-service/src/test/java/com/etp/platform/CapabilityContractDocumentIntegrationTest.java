package com.etp.platform;

import com.etp.platform.capability.Capability;
import com.etp.platform.capability.CapabilityContract;
import com.etp.platform.capability.CapabilityVersion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class CapabilityContractDocumentIntegrationTest extends AbstractPostgreSqlIntegrationTest {

    private static final String COMPOSITE_CONTRACT_JSON =
            """
            {
              "functional": {
                "capabilities": []
              },
              "interfaces": {
                "apis": [],
                "events": []
              },
              "configuration": {
                "parameters": []
              },
              "dependencies": {
                "capabilities": []
              },
              "security": {
                "authentication": [],
                "authorization": []
              },
              "nfr": {
                "availability": null,
                "performance": null,
                "scalability": null
              },
              "deployment": {
                "requirements": []
              },
              "operations": {
                "health": {},
                "metrics": {},
                "logging": {},
                "tracing": {}
              },
              "compatibility": {
                "platformVersions": []
              }
            }
            """;

    @Autowired
    private Flyway flyway;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void flywayMigrationV12AddsContractDocumentColumn() {
        assertThat(flyway.info().applied().length).isEqualTo(13);
    }

    @Test
    @Transactional
    void persistsAndReadsInlineContractDocument() throws Exception {
        Instant now = Instant.now();
        Capability capability = persistCapability(now);
        CapabilityVersion capabilityVersion = persistCapabilityVersion(capability, now);

        JsonNode contractDocument = objectMapper.readTree(COMPOSITE_CONTRACT_JSON);

        CapabilityContract contract = new CapabilityContract();
        contract.setContractId(UUID.randomUUID());
        contract.setCapabilityVersion(capabilityVersion);
        contract.setContractType("FULL");
        contract.setContractVersion("1.0.0");
        contract.setContractFormat("JSON");
        contract.setContractDocument(contractDocument);
        contract.setStatus("ACTIVE");
        contract.setCreatedAt(now);
        contract.setUpdatedAt(now);
        entityManager.persist(contract);
        entityManager.flush();
        entityManager.clear();

        CapabilityContract loaded =
                entityManager.find(CapabilityContract.class, contract.getContractId());

        assertThat(loaded.getContractDocument()).isNotNull();
        assertThat(loaded.getContractDocument().get("functional").get("capabilities").isArray()).isTrue();
        assertThat(loaded.getContractDocument().get("interfaces").get("apis").isArray()).isTrue();
        assertThat(loaded.getContractDocument().get("compatibility").get("platformVersions").isArray())
                .isTrue();
        assertThat(loaded.getContractLocation()).isNull();
    }

    @Test
    @Transactional
    void allowsNullContractDocumentForExternalContractReference() {
        Instant now = Instant.now();
        Capability capability = persistCapability(now);
        CapabilityVersion capabilityVersion = persistCapabilityVersion(capability, now);

        CapabilityContract contract = new CapabilityContract();
        contract.setContractId(UUID.randomUUID());
        contract.setCapabilityVersion(capabilityVersion);
        contract.setContractType("API");
        contract.setContractVersion("1.0.0");
        contract.setContractFormat("OPENAPI");
        contract.setContractLocation("https://example.com/contracts/etp.iam/openapi.yaml");
        contract.setContractDocument(null);
        contract.setStatus("ACTIVE");
        contract.setCreatedAt(now);
        contract.setUpdatedAt(now);
        entityManager.persist(contract);
        entityManager.flush();
        entityManager.clear();

        CapabilityContract loaded =
                entityManager.find(CapabilityContract.class, contract.getContractId());

        assertThat(loaded.getContractDocument()).isNull();
        assertThat(loaded.getContractLocation())
                .isEqualTo("https://example.com/contracts/etp.iam/openapi.yaml");
        assertThat(loaded.getContractFormat()).isEqualTo("OPENAPI");
    }

    private Capability persistCapability(Instant now) {
        Capability capability = new Capability();
        capability.setCapabilityId(UUID.randomUUID());
        capability.setCapabilityCode("etp.contract.test");
        capability.setCapabilityName("Contract Test Capability");
        capability.setLifecycleStatus("ACTIVE");
        capability.setCreatedAt(now);
        capability.setCreatedBy("test");
        capability.setUpdatedAt(now);
        capability.setUpdatedBy("test");
        entityManager.persist(capability);
        return capability;
    }

    private CapabilityVersion persistCapabilityVersion(Capability capability, Instant now) {
        CapabilityVersion capabilityVersion = new CapabilityVersion();
        capabilityVersion.setCapabilityVersionId(UUID.randomUUID());
        capabilityVersion.setCapability(capability);
        capabilityVersion.setVersion("1.0.0");
        capabilityVersion.setStatus("PUBLISHED");
        capabilityVersion.setCreatedAt(now);
        capabilityVersion.setCreatedBy("test");
        entityManager.persist(capabilityVersion);
        return capabilityVersion;
    }
}
