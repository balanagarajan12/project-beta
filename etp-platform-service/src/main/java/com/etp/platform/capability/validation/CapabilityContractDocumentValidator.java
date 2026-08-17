package com.etp.platform.capability.validation;

import com.etp.platform.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates inline capability contract documents against JSON Schema.
 * V1 applies a generic base schema; capability-specific schemas can be registered for future use.
 */
@Component
public class CapabilityContractDocumentValidator {

    private static final String BASE_SCHEMA_PATH = "schemas/capability-contract-base.schema.json";

    private final JsonSchemaFactory schemaFactory =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
    private final Map<String, JsonSchema> capabilitySchemas = new ConcurrentHashMap<>();
    private JsonSchema baseSchema;

    @PostConstruct
    void initialize() throws IOException {
        this.baseSchema = loadSchema(BASE_SCHEMA_PATH);
    }

    public void validate(String capabilityCode, JsonNode document) {
        if (document == null || document.isNull()) {
            return;
        }

        try {
            JsonSchema schema = capabilitySchemas.getOrDefault(capabilityCode, baseSchema);
            Set<ValidationMessage> errors = schema.validate(document);
            if (!errors.isEmpty()) {
                String message = errors.stream()
                        .map(ValidationMessage::getMessage)
                        .reduce((a, b) -> a + "; " + b)
                        .orElse("Invalid contract document");
                throw new BusinessException("INVALID_CAPABILITY_CONTRACT", message);
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(
                    "INVALID_CAPABILITY_CONTRACT",
                    "Invalid capability contract document: " + ex.getMessage());
        }
    }

    /**
     * Registers a capability-specific schema for future validation (e.g. etp.iam, etp.kafka).
     */
    public void registerCapabilitySchema(String capabilityCode, String classpathSchemaPath) throws IOException {
        capabilitySchemas.put(capabilityCode, loadSchema(classpathSchemaPath));
    }

    private JsonSchema loadSchema(String classpathLocation) throws IOException {
        try (InputStream inputStream = new ClassPathResource(classpathLocation).getInputStream()) {
            return schemaFactory.getSchema(inputStream);
        }
    }
}
