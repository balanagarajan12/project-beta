CREATE TABLE application_capability (
    application_capability_id UUID         NOT NULL,
    application_definition_id UUID         NOT NULL,
    capability_version_id     UUID         NOT NULL,
    usage_type                VARCHAR(30)  NOT NULL,
    configuration             JSONB,
    status                    VARCHAR(30)  NOT NULL,
    created_at                TIMESTAMPTZ  NOT NULL,
    created_by                VARCHAR(100) NOT NULL,
    CONSTRAINT pk_application_capability PRIMARY KEY (application_capability_id),
    CONSTRAINT uk_application_capability_definition_version UNIQUE (application_definition_id, capability_version_id),
    CONSTRAINT fk_application_capability_application_definition_id FOREIGN KEY (application_definition_id) REFERENCES application_definition (application_definition_id),
    CONSTRAINT fk_application_capability_capability_version_id FOREIGN KEY (capability_version_id) REFERENCES capability_version (capability_version_id)
);

CREATE INDEX idx_application_capability_application_definition_id ON application_capability (application_definition_id);
CREATE INDEX idx_application_capability_capability_version_id ON application_capability (capability_version_id);
