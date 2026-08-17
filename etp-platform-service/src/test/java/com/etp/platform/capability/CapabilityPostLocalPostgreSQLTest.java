package com.etp.platform.capability;

import com.etp.platform.capability.repository.CapabilityContractRepository;
import com.etp.platform.capability.repository.CapabilityDependencyRepository;
import com.etp.platform.capability.repository.CapabilityImplementationRepository;
import com.etp.platform.capability.repository.CapabilityRepository;
import com.etp.platform.capability.repository.CapabilityVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CapabilityPostLocalPostgreSQLTest {

    @DynamicPropertySource
    static void registerPostgreSQL(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5434/etp_platform");
        registry.add("spring.datasource.username", () -> "etp_admin");
        registry.add("spring.datasource.password", () -> "etp_platform_dev");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CapabilityRepository capabilityRepository;

    @Autowired
    private CapabilityVersionRepository capabilityVersionRepository;

    @Autowired
    private CapabilityContractRepository capabilityContractRepository;

    @Autowired
    private CapabilityDependencyRepository capabilityDependencyRepository;

    @Autowired
    private CapabilityImplementationRepository capabilityImplementationRepository;

    @BeforeEach
    void cleanCapabilities() {
        capabilityDependencyRepository.deleteAll();
        capabilityContractRepository.deleteAll();
        capabilityImplementationRepository.deleteAll();
        capabilityVersionRepository.deleteAll();
        capabilityRepository.deleteAll();
    }

    @Test
    void postCapabilityWithJsonDocumentReturns201AndPersistsJsonb() throws Exception {
        String body =
                """
                {
                  "code": "etp.debug.test",
                  "name": "Debug Test",
                  "category": "SECURITY",
                  "version": { "version": "1.0.0" },
                  "contract": {
                    "contractType": "COMPOSITE",
                    "contractVersion": "1.0",
                    "contractFormat": "JSON",
                    "document": {
                      "functional": { "capabilities": ["AUTHENTICATION"] },
                      "interfaces": {},
                      "configuration": {},
                      "security": {},
                      "nfr": {},
                      "deployment": {},
                      "operations": {},
                      "compatibility": {}
                    }
                  }
                }
                """;

        mockMvc.perform(post("/api/v1/capabilities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.code").value("etp.debug.test"))
                .andExpect(jsonPath("$.data.versions[0].contract.document.functional.capabilities[0]")
                        .value("AUTHENTICATION"));

        assertThat(capabilityRepository.findByCapabilityCode("etp.debug.test")).isPresent();
        assertThat(capabilityContractRepository.findAll()).hasSize(1);
        assertThat(capabilityContractRepository.findAll().getFirst().getContractDocument()).isNotNull();
        assertThat(capabilityContractRepository.findAll().getFirst().getContractDocument().isObject()).isTrue();
    }

    @Test
    void postCapabilityWithStringDocumentPlaceholderReturns400() throws Exception {
        String body =
                """
                {
                  "code": "etp.debug.string",
                  "name": "String Document",
                  "category": "SECURITY",
                  "version": { "version": "1.0.0" },
                  "contract": {
                    "contractType": "COMPOSITE",
                    "contractVersion": "1.0",
                    "contractFormat": "JSON",
                    "document": "string"
                  }
                }
                """;

        mockMvc.perform(post("/api/v1/capabilities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CAPABILITY_CONTRACT"));

        assertThat(capabilityRepository.findByCapabilityCode("etp.debug.string")).isEmpty();
    }
}
