CREATE TABLE application_definition (
    application_definition_id UUID         NOT NULL,
    application_id            UUID         NOT NULL,
    version                   VARCHAR(50)  NOT NULL,
    status                    VARCHAR(30)  NOT NULL,
    definition_document       JSONB,
    created_at                TIMESTAMPTZ  NOT NULL,
    created_by                VARCHAR(100) NOT NULL,
    approved_at               TIMESTAMPTZ,
    approved_by               VARCHAR(100),
    CONSTRAINT pk_application_definition PRIMARY KEY (application_definition_id),
    CONSTRAINT uk_application_definition_application_id_version UNIQUE (application_id, version),
    CONSTRAINT fk_application_definition_application_id FOREIGN KEY (application_id) REFERENCES application (application_id)
);

CREATE INDEX idx_application_definition_application_id ON application_definition (application_id);
