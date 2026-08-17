package com.etp.platform.common.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {

    public static final String ENTITY_CAPABILITY = "CAPABILITY";
    public static final String ENTITY_CAPABILITY_VERSION = "CAPABILITY_VERSION";

    public static final String ACTION_CREATE = "CREATE";
    public static final String ACTION_CREATE_VERSION = "CREATE_VERSION";
    public static final String ACTION_METADATA_UPDATE = "METADATA_UPDATE";
    public static final String ACTION_STATUS_CHANGE = "STATUS_CHANGE";

    public static final String PERFORMED_BY_SYSTEM = "etp-platform-system";

    private final AuditEventRepository auditEventRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuditService(AuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    public void record(
            String entityType,
            UUID entityId,
            String action,
            Map<String, Object> oldValue,
            Map<String, Object> newValue) {
        AuditEvent event = new AuditEvent();
        event.setAuditEventId(UUID.randomUUID());
        event.setEntityType(entityType);
        event.setEntityId(entityId);
        event.setAction(action);
        event.setOldValue(toJson(oldValue));
        event.setNewValue(toJson(newValue));
        event.setPerformedBy(PERFORMED_BY_SYSTEM);
        event.setPerformedAt(Instant.now());
        auditEventRepository.save(event);
    }

    private String toJson(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return value.toString();
        }
    }
}
