CREATE TABLE audit_event (
    audit_event_id UUID         NOT NULL,
    entity_type    VARCHAR(100) NOT NULL,
    entity_id      UUID         NOT NULL,
    action         VARCHAR(50)  NOT NULL,
    old_value      JSONB,
    new_value      JSONB,
    performed_by   VARCHAR(200) NOT NULL,
    performed_at     TIMESTAMPTZ  NOT NULL,
    correlation_id VARCHAR(100),
    CONSTRAINT pk_audit_event PRIMARY KEY (audit_event_id)
);

CREATE INDEX idx_audit_event_entity_type_entity_id ON audit_event (entity_type, entity_id);
CREATE INDEX idx_audit_event_performed_at ON audit_event (performed_at);
