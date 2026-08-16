CREATE TABLE capability_contract (
    contract_id           UUID         NOT NULL,
    capability_version_id UUID         NOT NULL,
    contract_type         VARCHAR(50)  NOT NULL,
    contract_version      VARCHAR(50)  NOT NULL,
    contract_format       VARCHAR(30)  NOT NULL,
    contract_location     VARCHAR(500),
    description           TEXT,
    status                VARCHAR(30)  NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_capability_contract PRIMARY KEY (contract_id),
    CONSTRAINT uk_capability_contract_version_type_version UNIQUE (capability_version_id, contract_type, contract_version),
    CONSTRAINT fk_capability_contract_capability_version_id FOREIGN KEY (capability_version_id) REFERENCES capability_version (capability_version_id)
);

CREATE INDEX idx_capability_contract_capability_version_id ON capability_contract (capability_version_id);
