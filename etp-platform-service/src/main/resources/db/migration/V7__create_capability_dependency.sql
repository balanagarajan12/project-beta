CREATE TABLE capability_dependency (
    dependency_id                    UUID        NOT NULL,
    capability_version_id            UUID        NOT NULL,
    depends_on_capability_version_id UUID        NOT NULL,
    dependency_type                  VARCHAR(30) NOT NULL,
    mandatory                        BOOLEAN     NOT NULL,
    created_at                       TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_capability_dependency PRIMARY KEY (dependency_id),
    CONSTRAINT uk_capability_dependency_versions UNIQUE (capability_version_id, depends_on_capability_version_id),
    CONSTRAINT fk_capability_dependency_capability_version_id FOREIGN KEY (capability_version_id) REFERENCES capability_version (capability_version_id),
    CONSTRAINT fk_capability_dependency_depends_on_version_id FOREIGN KEY (depends_on_capability_version_id) REFERENCES capability_version (capability_version_id),
    CONSTRAINT chk_capability_dependency_no_self CHECK (capability_version_id <> depends_on_capability_version_id)
);

CREATE INDEX idx_capability_dependency_capability_version_id ON capability_dependency (capability_version_id);
CREATE INDEX idx_capability_dependency_depends_on_version_id ON capability_dependency (depends_on_capability_version_id);
