package com.etp.platform;

import com.etp.platform.common.audit.AuditEventRepository;
import com.etp.platform.capability.repository.CapabilityContractRepository;
import com.etp.platform.capability.repository.CapabilityDependencyRepository;
import com.etp.platform.capability.repository.CapabilityImplementationRepository;
import com.etp.platform.capability.repository.CapabilityRepository;
import com.etp.platform.capability.repository.CapabilityVersionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class CapabilityApiIntegrationTest extends AbstractPostgreSqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CapabilityRepository capabilityRepository;

    @Autowired
    private CapabilityVersionRepository capabilityVersionRepository;

    @Autowired
    private CapabilityContractRepository capabilityContractRepository;

    @Autowired
    private CapabilityImplementationRepository capabilityImplementationRepository;

    @Autowired
    private CapabilityDependencyRepository capabilityDependencyRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @BeforeEach
    void cleanCapabilities() {
        auditEventRepository.deleteAll();
        capabilityDependencyRepository.deleteAll();
        capabilityContractRepository.deleteAll();
        capabilityImplementationRepository.deleteAll();
        capabilityVersionRepository.deleteAll();
        capabilityRepository.deleteAll();
    }

    @Test
    void postCreatesCapabilityAggregateWithImplementation() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/capabilities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createCapabilityJson("etp.iam", "IAM", "SECURITY", "1.0.0", true, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.code").value("etp.iam"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.versions[0].version").value("1.0.0"))
                .andExpect(jsonPath("$.data.versions[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.data.versions[0].contract.contractType").value("COMPOSITE"))
                .andExpect(jsonPath("$.data.versions[0].contract.document.functional.capabilities[0]").value("AUTHENTICATION"))
                .andExpect(jsonPath("$.data.versions[0].implementation.technology").value("Keycloak"))
                .andReturn();

        String capabilityId = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asText();
        assertThat(capabilityRepository.findById(UUID.fromString(capabilityId))).isPresent();
        assertThat(auditEventRepository.count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void postCreatesCapabilityWithDependency() throws Exception {
        MvcResult base = mockMvc.perform(post("/api/v1/capabilities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createCapabilityJson("etp.rest", "REST", "INTEGRATION", "1.0.0", false, null)))
                .andExpect(status().isCreated())
                .andReturn();

        String dependencyVersionId = objectMapper.readTree(base.getResponse().getContentAsString())
                .path("data").path("versions").get(0).path("id").asText();

        mockMvc.perform(post("/api/v1/capabilities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createCapabilityJson("etp.kafka", "Kafka", "INTEGRATION", "1.0.0", false, dependencyVersionId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.versions[0].dependencies[0].capabilityVersionId").value(dependencyVersionId));
    }

    @Test
    void postRollsBackOnInvalidDependency() throws Exception {
        UUID missingVersionId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/capabilities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createCapabilityJson("etp.sftp", "SFTP", "INTEGRATION", "1.0.0", false, missingVersionId.toString())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_NOT_FOUND"));

        assertThat(capabilityRepository.findByCapabilityCode("etp.sftp")).isEmpty();
    }

    @Test
    void postDuplicateCodeReturns409() throws Exception {
        mockMvc.perform(post("/api/v1/capabilities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createCapabilityJson("etp.postgresql", "PostgreSQL", "DATA", "1.0.0", false, null)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/capabilities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createCapabilityJson("etp.postgresql", "PostgreSQL", "DATA", "1.0.0", false, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_CAPABILITY_CODE"));
    }

    @Test
    void postValidationErrorReturns400() throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", "Missing Code");

        mockMvc.perform(post("/api/v1/capabilities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void postRejectsInvalidContractDocument() throws Exception {
        ObjectNode document = objectMapper.createObjectNode();
        document.put("functional", "invalid");

        mockMvc.perform(post("/api/v1/capabilities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createCapabilityJsonWithDocument("etp.flowable", "Flowable", "WORKFLOW", "1.0.0", document)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CAPABILITY_CONTRACT"));
    }

    @Test
    void postSupportsExternalContractReference() throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("code", "etp.rabbitmq");
        body.put("name", "RabbitMQ");
        body.put("category", "INTEGRATION");
        ObjectNode version = body.putObject("version");
        version.put("version", "1.0.0");
        ObjectNode contract = body.putObject("contract");
        contract.put("contractType", "API");
        contract.put("contractVersion", "1.0");
        contract.put("contractFormat", "OPENAPI");
        contract.put("location", "https://example.com/rabbitmq-openapi.yaml");

        mockMvc.perform(post("/api/v1/capabilities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.versions[0].contract.location")
                        .value("https://example.com/rabbitmq-openapi.yaml"))
                .andExpect(jsonPath("$.data.versions[0].contract.document").doesNotExist());
    }

    @Test
    void getListsCapabilitiesWithFilters() throws Exception {
        mockMvc.perform(post("/api/v1/capabilities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createCapabilityJson("etp.iam", "IAM", "SECURITY", "1.0.0", true, null)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].version").value("1.0.0"))
                .andExpect(jsonPath("$.data[0].code").value("etp.iam"))
                .andExpect(jsonPath("$.data[0].createdAt").exists())
                .andExpect(jsonPath("$.data[0].updatedAt").exists())
                .andExpect(jsonPath("$.data[0].contract").doesNotExist());

        mockMvc.perform(get("/api/v1/capabilities?category=SECURITY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        mockMvc.perform(get("/api/v1/capabilities?status=DRAFT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        mockMvc.perform(get("/api/v1/capabilities?category=DATA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void getCapabilityByIdReturnsAggregate() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/capabilities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createCapabilityJson("etp.iam", "IAM", "SECURITY", "1.0.0", true, null)))
                .andExpect(status().isCreated())
                .andReturn();

        String capabilityId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asText();

        mockMvc.perform(get("/api/v1/capabilities/" + capabilityId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(capabilityId))
                .andExpect(jsonPath("$.data.versions[0].contract.document.interfaces.apis[0]").value("REST"));
    }

    @Test
    void getUnknownCapabilityReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/capabilities/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CAPABILITY_NOT_FOUND"));
    }

    @Test
    void putUpdatesCapabilityMetadata() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/capabilities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createCapabilityJson("etp.iam", "IAM", "SECURITY", "1.0.0", true, null)))
                .andExpect(status().isCreated())
                .andReturn();

        String capabilityId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asText();

        ObjectNode update = objectMapper.createObjectNode();
        update.put("name", "Identity and Access Management");
        update.put("description", "Updated IAM capability");
        update.put("category", "SECURITY");
        update.put("status", "DRAFT");

        mockMvc.perform(put("/api/v1/capabilities/" + capabilityId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(update.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Identity and Access Management"))
                .andExpect(jsonPath("$.data.description").value("Updated IAM capability"))
                .andExpect(jsonPath("$.data.version").value("1.0.0"))
                .andExpect(jsonPath("$.data.createdAt").exists())
                .andExpect(jsonPath("$.data.updatedAt").exists());
    }

    @Test
    void putInvalidRequestReturns400() throws Exception {
        String capabilityId = createCapabilityAndReturnId("etp.iam", "IAM", "SECURITY");

        ObjectNode update = objectMapper.createObjectNode();
        update.put("description", "Missing required fields");

        mockMvc.perform(put("/api/v1/capabilities/" + capabilityId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(update.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void putUnknownCapabilityReturns404() throws Exception {
        ObjectNode update = objectMapper.createObjectNode();
        update.put("name", "Unknown");
        update.put("category", "SECURITY");
        update.put("status", "DRAFT");

        mockMvc.perform(put("/api/v1/capabilities/" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(update.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CAPABILITY_NOT_FOUND"));
    }

    @Test
    void patchUpdatesMetadataAndCreatesNewVersion() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/capabilities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createCapabilityJson("etp.iam", "IAM", "SECURITY", "1.0.0", true, null)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode createdJson = objectMapper.readTree(created.getResponse().getContentAsString());
        String capabilityId = createdJson.path("data").path("id").asText();
        String version1Id = createdJson.path("data").path("versions").get(0).path("id").asText();

        ObjectNode metadataPatch = objectMapper.createObjectNode();
        metadataPatch.put("description", "Updated description");

        mockMvc.perform(patch("/api/v1/capabilities/" + capabilityId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(metadataPatch.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.description").value("Updated description"));

        ObjectNode newVersionPatch = objectMapper.createObjectNode();
        ObjectNode newVersion = newVersionPatch.putObject("newVersion");
        newVersion.put("version", "1.1.0");
        newVersion.put("description", "Enhanced authorization");
        ObjectNode contract = newVersion.putObject("contract");
        contract.put("contractType", "COMPOSITE");
        contract.put("contractVersion", "1.1");
        contract.put("contractFormat", "JSON");
        contract.set("document", minimalContractDocument());
        ObjectNode implementation = newVersion.putObject("implementation");
        implementation.put("code", "etp-iam");
        implementation.put("name", "ETP IAM Service");
        implementation.put("technology", "Keycloak");
        implementation.put("technologyVersion", "27.x");

        mockMvc.perform(patch("/api/v1/capabilities/" + capabilityId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newVersionPatch.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versions.length()").value(2))
                .andExpect(jsonPath("$.data.versions[0].version").value("1.1.0"))
                .andExpect(jsonPath("$.data.versions[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.data.versions[1].version").value("1.0.0"))
                .andExpect(jsonPath("$.data.versions[1].id").value(version1Id))
                .andExpect(jsonPath("$.data.versions[1].implementation.technologyVersion").value("26.x"));
    }

    @Test
    void patchDuplicateVersionReturns409() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/capabilities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createCapabilityJson("etp.iam", "IAM", "SECURITY", "1.0.0", true, null)))
                .andExpect(status().isCreated())
                .andReturn();

        String capabilityId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asText();

        ObjectNode newVersionPatch = objectMapper.createObjectNode();
        ObjectNode newVersion = newVersionPatch.putObject("newVersion");
        newVersion.put("version", "1.0.0");
        ObjectNode contract = newVersion.putObject("contract");
        contract.put("contractType", "COMPOSITE");
        contract.put("contractVersion", "1.0");
        contract.put("contractFormat", "JSON");
        contract.set("document", minimalContractDocument());

        mockMvc.perform(patch("/api/v1/capabilities/" + capabilityId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newVersionPatch.toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_CAPABILITY_VERSION"));
    }

    @Test
    void patchCapabilityLifecycleTransitions() throws Exception {
        String capabilityId = createCapabilityAndReturnId("etp.iam", "IAM", "SECURITY");

        transitionCapabilityToActive(capabilityId);

        mockMvc.perform(patch("/api/v1/capabilities/" + capabilityId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DEPRECATED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DEPRECATED"));

        mockMvc.perform(patch("/api/v1/capabilities/" + capabilityId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RETIRED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RETIRED"));
    }

    @Test
    void patchInvalidCapabilityLifecycleTransitionReturns409() throws Exception {
        String capabilityId = createCapabilityAndReturnId("etp.iam", "IAM", "SECURITY");

        mockMvc.perform(patch("/api/v1/capabilities/" + capabilityId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RETIRED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_LIFECYCLE_TRANSITION"));
    }

    @Test
    void patchVersionLifecycleTransitions() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/capabilities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createCapabilityJson("etp.iam", "IAM", "SECURITY", "1.0.0", true, null)))
                .andExpect(status().isCreated())
                .andReturn();

        String capabilityId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asText();
        String versionId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("versions").get(0).path("id").asText();

        patchVersionStatus(capabilityId, versionId, "VALIDATED");
        patchVersionStatus(capabilityId, versionId, "ACTIVE");
        patchVersionStatus(capabilityId, versionId, "DEPRECATED");
        patchVersionStatus(capabilityId, versionId, "RETIRED");

        mockMvc.perform(get("/api/v1/capabilities/" + capabilityId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versions[0].status").value("RETIRED"));
    }

    @Test
    void patchInvalidVersionLifecycleTransitionReturns409() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/capabilities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createCapabilityJson("etp.iam", "IAM", "SECURITY", "1.0.0", true, null)))
                .andExpect(status().isCreated())
                .andReturn();

        String capabilityId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asText();
        String versionId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("versions").get(0).path("id").asText();

        ObjectNode patch = objectMapper.createObjectNode();
        ObjectNode versionStatus = patch.putObject("versionStatus");
        versionStatus.put("versionId", versionId);
        versionStatus.put("status", "ACTIVE");

        mockMvc.perform(patch("/api/v1/capabilities/" + capabilityId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patch.toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_LIFECYCLE_TRANSITION"));
    }

    @Test
    void activeVersionContentRemainsImmutableWhenNewVersionCreated() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/capabilities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createCapabilityJson("etp.iam", "IAM", "SECURITY", "1.0.0", true, null)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode createdJson = objectMapper.readTree(created.getResponse().getContentAsString());
        String capabilityId = createdJson.path("data").path("id").asText();
        String versionId = createdJson.path("data").path("versions").get(0).path("id").asText();

        patchVersionStatus(capabilityId, versionId, "VALIDATED");
        patchVersionStatus(capabilityId, versionId, "ACTIVE");

        ObjectNode newVersionPatch = objectMapper.createObjectNode();
        ObjectNode newVersion = newVersionPatch.putObject("newVersion");
        newVersion.put("version", "1.1.0");
        ObjectNode contract = newVersion.putObject("contract");
        contract.put("contractType", "COMPOSITE");
        contract.put("contractVersion", "1.1");
        contract.put("contractFormat", "JSON");
        contract.set("document", minimalContractDocument());

        mockMvc.perform(patch("/api/v1/capabilities/" + capabilityId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newVersionPatch.toString()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/capabilities/" + capabilityId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versions[1].version").value("1.0.0"))
                .andExpect(jsonPath("$.data.versions[1].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.versions[1].implementation.technologyVersion").value("26.x"));
    }

    private String createCapabilityAndReturnId(String code, String name, String category) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/capabilities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createCapabilityJson(code, name, category, "1.0.0", true, null)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asText();
    }

    private void transitionCapabilityToActive(String capabilityId) throws Exception {
        mockMvc.perform(patch("/api/v1/capabilities/" + capabilityId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
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

    private String createCapabilityJson(
            String code,
            String name,
            String category,
            String version,
            boolean withImplementation,
            String dependencyVersionId) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("code", code);
        body.put("name", name);
        body.put("category", category);
        ObjectNode versionNode = body.putObject("version");
        versionNode.put("version", version);
        ObjectNode contract = body.putObject("contract");
        contract.put("contractType", "COMPOSITE");
        contract.put("contractVersion", "1.0");
        contract.put("contractFormat", "JSON");
        contract.set("document", minimalContractDocument());
        if (withImplementation) {
            ObjectNode implementation = body.putObject("implementation");
            implementation.put("code", "etp-iam");
            implementation.put("name", "ETP IAM Service");
            implementation.put("technology", "Keycloak");
            implementation.put("technologyVersion", "26.x");
        }
        if (dependencyVersionId != null) {
            ObjectNode dependency = body.putArray("dependencies").addObject();
            dependency.put("capabilityVersionId", dependencyVersionId);
            dependency.put("dependencyType", "RUNTIME");
            dependency.put("mandatory", true);
        }
        return body.toString();
    }

    private String createCapabilityJsonWithDocument(
            String code,
            String name,
            String category,
            String version,
            JsonNode document) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("code", code);
        body.put("name", name);
        body.put("category", category);
        ObjectNode versionNode = body.putObject("version");
        versionNode.put("version", version);
        ObjectNode contract = body.putObject("contract");
        contract.put("contractType", "COMPOSITE");
        contract.put("contractVersion", "1.0");
        contract.put("contractFormat", "JSON");
        contract.set("document", document);
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
}
