CREATE TABLE capability_version (
    capability_version_id  UUID         NOT NULL,
    capability_id          UUID         NOT NULL,
    version                VARCHAR(50)  NOT NULL,
    status                 VARCHAR(30)  NOT NULL,
    contract_version       VARCHAR(50),
    min_platform_version   VARCHAR(50),
    release_notes          TEXT,
    effective_from         TIMESTAMPTZ,
    deprecated_at          TIMESTAMPTZ,
    created_at             TIMESTAMPTZ  NOT NULL,
    created_by             VARCHAR(100) NOT NULL,
    CONSTRAINT pk_capability_version PRIMARY KEY (capability_version_id),
    CONSTRAINT uk_capability_version_capability_id_version UNIQUE (capability_id, version),
    CONSTRAINT fk_capability_version_capability_id FOREIGN KEY (capability_id) REFERENCES capability (capability_id)
);

CREATE INDEX idx_capability_version_capability_id ON capability_version (capability_id);
