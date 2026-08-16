CREATE TABLE capability_implementation (
    implementation_id       UUID         NOT NULL,
    capability_version_id   UUID         NOT NULL,
    implementation_code     VARCHAR(100) NOT NULL,
    implementation_name     VARCHAR(200) NOT NULL,
    technology_name         VARCHAR(200) NOT NULL,
    technology_version      VARCHAR(100),
    implementation_type     VARCHAR(50),
    configuration_reference VARCHAR(500),
    status                  VARCHAR(30)  NOT NULL,
    created_at              TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_capability_implementation PRIMARY KEY (implementation_id),
    CONSTRAINT uk_capability_implementation_version_code UNIQUE (capability_version_id, implementation_code),
    CONSTRAINT fk_capability_implementation_capability_version_id FOREIGN KEY (capability_version_id) REFERENCES capability_version (capability_version_id)
);

CREATE INDEX idx_capability_implementation_capability_version_id ON capability_implementation (capability_version_id);
