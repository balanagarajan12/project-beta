package com.etp.platform.capability;

import com.etp.platform.capability.domain.CapabilityVersionLifecycleStatus;
import com.etp.platform.capability.repository.CapabilityContractRepository;
import com.etp.platform.capability.repository.CapabilityDependencyRepository;
import com.etp.platform.capability.repository.CapabilityImplementationRepository;
import com.etp.platform.capability.repository.CapabilityRepository;
import com.etp.platform.capability.repository.CapabilityVersionRepository;
import com.etp.platform.common.audit.AuditEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CapabilityVersionLifecyclePostgreSQLTest {

    @DynamicPropertySource
    static void registerPostgreSQL(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5434/etp_platform");
        registry.add("spring.datasource.username", () -> "etp_admin");
        registry.add("spring.datasource.password", () -> "etp_platform_dev");
    }

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

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

    @Autowired
    private AuditEventRepository auditEventRepository;

    @BeforeEach
    void cleanData() {
        auditEventRepository.deleteAll();
        capabilityDependencyRepository.deleteAll();
        capabilityContractRepository.deleteAll();
        capabilityImplementationRepository.deleteAll();
        capabilityVersionRepository.deleteAll();
        capabilityRepository.deleteAll();
    }

    @Test
    void draftToValidatedWorks() throws Exception {
        CapabilityContext context = createCapability("etp.lifecycle.draftvalidated", "1.0.0");
        patchVersionStatus(context.capabilityId, context.versionId, "VALIDATED");
        assertVersionStatus(context.versionId, CapabilityVersionLifecycleStatus.VALIDATED.name());
    }

    @Test
    void draftToCancelledWorks() throws Exception {
        CapabilityContext context = createCapability("etp.lifecycle.draftcancelled", "1.0.0");
        patchVersionStatus(context.capabilityId, context.versionId, "CANCELLED");
        assertVersionStatus(context.versionId, CapabilityVersionLifecycleStatus.CANCELLED.name());
        assertThat(auditEventRepository.count()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void validatedToCancelledWorks() throws Exception {
        CapabilityContext context = createCapability("etp.lifecycle.validatedcancelled", "1.0.0");
        patchVersionStatus(context.capabilityId, context.versionId, "VALIDATED");
        patchVersionStatus(context.capabilityId, context.versionId, "CANCELLED");
        assertVersionStatus(context.versionId, CapabilityVersionLifecycleStatus.CANCELLED.name());
    }

    @Test
    void cancelledToValidatedFails() throws Exception {
        CapabilityContext context = createCapability("etp.lifecycle.cancelledvalidated", "1.0.0");
        patchVersionStatus(context.capabilityId, context.versionId, "CANCELLED");
        expectVersionStatusConflict(context.capabilityId, context.versionId, "VALIDATED");
    }

    @Test
    void cancelledToActiveFails() throws Exception {
        CapabilityContext context = createCapability("etp.lifecycle.cancelledactive", "1.0.0");
        patchVersionStatus(context.capabilityId, context.versionId, "CANCELLED");
        expectVersionStatusConflict(context.capabilityId, context.versionId, "ACTIVE");
    }

    @Test
    void activeToCancelledFails() throws Exception {
        CapabilityContext context = createCapability("etp.lifecycle.activecancelled", "1.0.0");
        activateVersion(context);
        expectVersionStatusConflict(context.capabilityId, context.versionId, "CANCELLED");
    }

    @Test
    void deprecatedToCancelledFails() throws Exception {
        CapabilityContext context = createCapability("etp.lifecycle.deprecatedcancelled", "1.0.0");
        activateVersion(context);
        patchVersionStatus(context.capabilityId, context.versionId, "DEPRECATED");
        expectVersionStatusConflict(context.capabilityId, context.versionId, "CANCELLED");
    }

    @Test
    void retiredToCancelledFails() throws Exception {
        CapabilityContext context = createCapability("etp.lifecycle.retiredcancelled", "1.0.0");
        activateVersion(context);
        patchVersionStatus(context.capabilityId, context.versionId, "DEPRECATED");
        patchVersionStatus(context.capabilityId, context.versionId, "RETIRED");
        expectVersionStatusConflict(context.capabilityId, context.versionId, "CANCELLED");
    }

    @Test
    void deprecatedToActiveFails() throws Exception {
        CapabilityContext context = createCapability("etp.lifecycle.deprecatedactive", "1.0.0");
        activateVersion(context);
        patchVersionStatus(context.capabilityId, context.versionId, "DEPRECATED");
        expectVersionStatusConflict(context.capabilityId, context.versionId, "ACTIVE");
    }

    @Test
    void retiredToActiveFails() throws Exception {
        CapabilityContext context = createCapability("etp.lifecycle.retiredactive", "1.0.0");
        activateVersion(context);
        patchVersionStatus(context.capabilityId, context.versionId, "DEPRECATED");
        patchVersionStatus(context.capabilityId, context.versionId, "RETIRED");
        expectVersionStatusConflict(context.capabilityId, context.versionId, "ACTIVE");
    }

    @Test
    void firstVersionActivationAutomaticallyActivatesCapability() throws Exception {
        CapabilityContext context = createCapability("etp.lifecycle.firstactive", "1.0.0");
        patchVersionStatus(context.capabilityId, context.versionId, "VALIDATED");
        patchVersionStatus(context.capabilityId, context.versionId, "ACTIVE");

        mockMvc.perform(get("/api/v1/capabilities/" + context.capabilityId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.versions[0].status").value("ACTIVE"));
    }

    @Test
    void activatingNewVersionDeprecatesPreviousActiveVersion() throws Exception {
        CapabilityContext context = createCapability("etp.lifecycle.replaceactive", "1.0.0");
        activateVersion(context);

        String version11Id = createAdditionalVersion(context.capabilityId, "1.1.0");
        patchVersionStatus(context.capabilityId, version11Id, "VALIDATED");
        patchVersionStatus(context.capabilityId, version11Id, "ACTIVE");

        assertVersionStatus(context.versionId, CapabilityVersionLifecycleStatus.DEPRECATED.name());
        assertVersionStatus(version11Id, CapabilityVersionLifecycleStatus.ACTIVE.name());
        assertThat(capabilityVersionRepository.findByCapabilityCapabilityIdAndStatus(
                context.capabilityIdUuid(), CapabilityVersionLifecycleStatus.ACTIVE.name())).isPresent();
    }

    @Test
    void twoActiveVersionsCanNeverExist() throws Exception {
        CapabilityContext context = createCapability("etp.lifecycle.singleactive", "1.0.0");
        activateVersion(context);

        String version11Id = createAdditionalVersion(context.capabilityId, "1.1.0");
        patchVersionStatus(context.capabilityId, version11Id, "VALIDATED");
        patchVersionStatus(context.capabilityId, version11Id, "ACTIVE");

        long activeCount = capabilityVersionRepository.findByCapabilityCapabilityIdOrderByCreatedAtDesc(context.capabilityIdUuid())
                .stream()
                .filter(version -> CapabilityVersionLifecycleStatus.ACTIVE.name().equals(version.getStatus()))
                .count();
        assertThat(activeCount).isEqualTo(1);
    }

    @Test
    void olderValidatedVersionCannotBeActivatedBelowCurrentActive() throws Exception {
        CapabilityContext context = createCapability("etp.lifecycle.blockolder", "1.0.0");
        activateVersion(context);

        String version09Id = createAdditionalVersion(context.capabilityId, "0.9.0");
        patchVersionStatus(context.capabilityId, version09Id, "VALIDATED");
        expectVersionStatusConflict(context.capabilityId, version09Id, "ACTIVE");

        assertVersionStatus(context.versionId, CapabilityVersionLifecycleStatus.ACTIVE.name());
        assertVersionStatus(version09Id, CapabilityVersionLifecycleStatus.VALIDATED.name());
    }

    @Test
    void historicalVersionsRemainQueryable() throws Exception {
        CapabilityContext context = createCapability("etp.lifecycle.history", "1.0.0");
        activateVersion(context);
        String version11Id = createAdditionalVersion(context.capabilityId, "1.1.0");
        patchVersionStatus(context.capabilityId, version11Id, "VALIDATED");
        patchVersionStatus(context.capabilityId, version11Id, "ACTIVE");

        mockMvc.perform(get("/api/v1/capabilities/" + context.capabilityId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versions.length()").value(2));

        JsonNode versions = objectMapper.readTree(
                mockMvc.perform(get("/api/v1/capabilities/" + context.capabilityId))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .path("data").path("versions");

        assertThat(findVersionStatus(versions, "1.0.0")).isEqualTo("DEPRECATED");
        assertThat(findVersionStatus(versions, "1.1.0")).isEqualTo("ACTIVE");
    }

    @Test
    void activationFailureDoesNotDeprecateCurrentActiveVersion() throws Exception {
        CapabilityContext context = createCapability("etp.lifecycle.nopartialdeprecate", "1.0.0");
        activateVersion(context);

        String version09Id = createAdditionalVersion(context.capabilityId, "0.9.0");
        patchVersionStatus(context.capabilityId, version09Id, "VALIDATED");
        expectVersionStatusConflict(context.capabilityId, version09Id, "ACTIVE");

        assertVersionStatus(context.versionId, CapabilityVersionLifecycleStatus.ACTIVE.name());
    }

    @Test
    void statusChangeAuditEventsAreRecorded() throws Exception {
        CapabilityContext context = createCapability("etp.lifecycle.audit", "1.0.0");
        long before = auditEventRepository.count();
        patchVersionStatus(context.capabilityId, context.versionId, "CANCELLED");
        assertThat(auditEventRepository.count()).isGreaterThan(before);
    }

    private CapabilityContext createCapability(String code, String version) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/capabilities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createCapabilityJson(code, version)))
                .andExpect(status().isCreated())
                .andReturn();

        String capabilityId = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asText();
        String versionId = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("versions").get(0).path("id").asText();
        return new CapabilityContext(capabilityId, versionId);
    }

    private String createAdditionalVersion(String capabilityId, String version) throws Exception {
        ObjectNode patch = objectMapper.createObjectNode();
        ObjectNode newVersion = patch.putObject("newVersion");
        newVersion.put("version", version);
        ObjectNode contract = newVersion.putObject("contract");
        contract.put("contractType", "COMPOSITE");
        contract.put("contractVersion", "1.0");
        contract.put("contractFormat", "JSON");
        contract.set("document", minimalContractDocument());

        MvcResult result = mockMvc.perform(patch("/api/v1/capabilities/" + capabilityId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patch.toString()))
                .andExpect(status().isOk())
                .andReturn();

        return findVersionId(
                objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("versions"),
                version);
    }

    private void activateVersion(CapabilityContext context) throws Exception {
        patchVersionStatus(context.capabilityId, context.versionId, "VALIDATED");
        patchVersionStatus(context.capabilityId, context.versionId, "ACTIVE");
    }

    private void patchVersionStatus(String capabilityId, String versionId, String status) throws Exception {
        ObjectNode patch = objectMapper.createObjectNode();
        ObjectNode versionStatus = patch.putObject("versionStatus");
        versionStatus.put("versionId", versionId);
        versionStatus.put("status", status);

        mockMvc.perform(patch("/api/v1/capabilities/" + capabilityId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patch.toString()))
                .andExpect(status().isOk());
    }

    private void expectVersionStatusConflict(String capabilityId, String versionId, String status) throws Exception {
        ObjectNode patch = objectMapper.createObjectNode();
        ObjectNode versionStatus = patch.putObject("versionStatus");
        versionStatus.put("versionId", versionId);
        versionStatus.put("status", status);

        mockMvc.perform(patch("/api/v1/capabilities/" + capabilityId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patch.toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_LIFECYCLE_TRANSITION"));
    }

    private void assertVersionStatus(String versionId, String expectedStatus) {
        assertThat(capabilityVersionRepository.findById(UUID.fromString(versionId)))
                .isPresent()
                .get()
                .extracting(com.etp.platform.capability.CapabilityVersion::getStatus)
                .isEqualTo(expectedStatus);
    }

    private String createCapabilityJson(String code, String version) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("code", code);
        body.put("name", code);
        body.put("category", "SECURITY");
        ObjectNode versionNode = body.putObject("version");
        versionNode.put("version", version);
        ObjectNode contract = body.putObject("contract");
        contract.put("contractType", "COMPOSITE");
        contract.put("contractVersion", "1.0");
        contract.put("contractFormat", "JSON");
        contract.set("document", minimalContractDocument());
        return body.toString();
    }

    private ObjectNode minimalContractDocument() {
        ObjectNode document = objectMapper.createObjectNode();
        ObjectNode functional = document.putObject("functional");
        functional.putArray("capabilities").add("AUTHENTICATION");
        ObjectNode interfaces = document.putObject("interfaces");
        interfaces.putArray("apis").add("REST");
        document.set("configuration", objectMapper.createObjectNode());
        document.set("security", objectMapper.createObjectNode());
        document.set("nfr", objectMapper.createObjectNode());
        document.set("deployment", objectMapper.createObjectNode());
        document.set("operations", objectMapper.createObjectNode());
        document.set("compatibility", objectMapper.createObjectNode());
        return document;
    }

    private String findVersionId(JsonNode versions, String version) {
        for (JsonNode versionNode : versions) {
            if (versionNode.path("version").asText().equals(version)) {
                return versionNode.path("id").asText();
            }
        }
        throw new IllegalStateException("Version not found: " + version);
    }

    private String findVersionStatus(JsonNode versions, String version) {
        for (JsonNode versionNode : versions) {
            if (versionNode.path("version").asText().equals(version)) {
                return versionNode.path("status").asText();
            }
        }
        throw new IllegalStateException("Version not found: " + version);
    }

    private record CapabilityContext(String capabilityId, String versionId) {
        UUID capabilityIdUuid() {
            return UUID.fromString(capabilityId);
        }
    }
}
