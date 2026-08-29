CREATE TABLE operating_model (
    operating_model_id   UUID         NOT NULL,
    operating_model_code VARCHAR(4)   NOT NULL,
    operating_model_name VARCHAR(100) NOT NULL,
    description          VARCHAR(500),
    status               VARCHAR(30)  NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL,
    created_by           VARCHAR(100) NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,
    updated_by           VARCHAR(100) NOT NULL,
    CONSTRAINT pk_operating_model PRIMARY KEY (operating_model_id),
    CONSTRAINT uk_operating_model_operating_model_code UNIQUE (operating_model_code)
);

INSERT INTO operating_model (
    operating_model_id,
    operating_model_code,
    operating_model_name,
    description,
    status,
    created_at,
    created_by,
    updated_at,
    updated_by
) VALUES
    (
        'a0000001-0000-4000-8000-000000000001',
        'AAS',
        'Application-as-a-Service',
        NULL,
        'ACTIVE',
        NOW(),
        'system',
        NOW(),
        'system'
    ),
    (
        'a0000002-0000-4000-8000-000000000002',
        'CAP',
        'Capability Subscriber',
        NULL,
        'ACTIVE',
        NOW(),
        'system',
        NOW(),
        'system'
    ),
    (
        'a0000003-0000-4000-8000-000000000003',
        'ENT',
        'Enterprise',
        NULL,
        'ACTIVE',
        NOW(),
        'system',
        NOW(),
        'system'
    );

CREATE TABLE customer_operating_access (
    customer_operating_access_id UUID        NOT NULL,
    customer_id                  UUID        NOT NULL,
    operating_model_id           UUID        NOT NULL,
    status                       VARCHAR(30) NOT NULL,
    effective_from               TIMESTAMPTZ NOT NULL,
    effective_to                 TIMESTAMPTZ,
    created_at                   TIMESTAMPTZ NOT NULL,
    created_by                   VARCHAR(100) NOT NULL,
    updated_at                   TIMESTAMPTZ NOT NULL,
    updated_by                   VARCHAR(100) NOT NULL,
    CONSTRAINT pk_customer_operating_access PRIMARY KEY (customer_operating_access_id),
    CONSTRAINT uk_customer_operating_access_customer_model_from UNIQUE (customer_id, operating_model_id, effective_from),
    CONSTRAINT fk_customer_operating_access_customer_id FOREIGN KEY (customer_id) REFERENCES customer (customer_id),
    CONSTRAINT fk_customer_operating_access_operating_model_id FOREIGN KEY (operating_model_id) REFERENCES operating_model (operating_model_id),
    CONSTRAINT chk_customer_operating_access_effective_range CHECK (effective_to IS NULL OR effective_to > effective_from)
);

CREATE INDEX idx_customer_operating_access_customer_id ON customer_operating_access (customer_id);
CREATE INDEX idx_customer_operating_access_operating_model_id ON customer_operating_access (operating_model_id);

CREATE TABLE capability_entitlement (
    capability_entitlement_id UUID        NOT NULL,
    customer_id               UUID        NOT NULL,
    capability_version_id     UUID        NOT NULL,
    status                    VARCHAR(30) NOT NULL,
    effective_from            TIMESTAMPTZ NOT NULL,
    effective_to              TIMESTAMPTZ,
    source                    VARCHAR(4),
    quantity                  INTEGER,
    max_applications          INTEGER,
    created_at                TIMESTAMPTZ NOT NULL,
    created_by                VARCHAR(100) NOT NULL,
    updated_at                TIMESTAMPTZ NOT NULL,
    updated_by                VARCHAR(100) NOT NULL,
    CONSTRAINT pk_capability_entitlement PRIMARY KEY (capability_entitlement_id),
    CONSTRAINT uk_capability_entitlement_customer_version_from UNIQUE (customer_id, capability_version_id, effective_from),
    CONSTRAINT fk_capability_entitlement_customer_id FOREIGN KEY (customer_id) REFERENCES customer (customer_id),
    CONSTRAINT fk_capability_entitlement_capability_version_id FOREIGN KEY (capability_version_id) REFERENCES capability_version (capability_version_id),
    CONSTRAINT chk_capability_entitlement_effective_range CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT chk_capability_entitlement_quantity_positive CHECK (quantity IS NULL OR quantity > 0),
    CONSTRAINT chk_capability_entitlement_max_applications_positive CHECK (max_applications IS NULL OR max_applications > 0)
);

CREATE INDEX idx_capability_entitlement_customer_id ON capability_entitlement (customer_id);
CREATE INDEX idx_capability_entitlement_capability_version_id ON capability_entitlement (capability_version_id);

CREATE TABLE application_entitlement (
    application_entitlement_id UUID        NOT NULL,
    customer_id                UUID        NOT NULL,
    application_id             UUID        NOT NULL,
    status                     VARCHAR(30) NOT NULL,
    effective_from             TIMESTAMPTZ NOT NULL,
    effective_to               TIMESTAMPTZ,
    source                     VARCHAR(4),
    created_at                 TIMESTAMPTZ NOT NULL,
    created_by                 VARCHAR(100) NOT NULL,
    updated_at                 TIMESTAMPTZ NOT NULL,
    updated_by                 VARCHAR(100) NOT NULL,
    CONSTRAINT pk_application_entitlement PRIMARY KEY (application_entitlement_id),
    CONSTRAINT uk_application_entitlement_customer_application_from UNIQUE (customer_id, application_id, effective_from),
    CONSTRAINT fk_application_entitlement_customer_id FOREIGN KEY (customer_id) REFERENCES customer (customer_id),
    CONSTRAINT fk_application_entitlement_application_id FOREIGN KEY (application_id) REFERENCES application (application_id),
    CONSTRAINT chk_application_entitlement_effective_range CHECK (effective_to IS NULL OR effective_to > effective_from)
);

CREATE INDEX idx_application_entitlement_customer_id ON application_entitlement (customer_id);
CREATE INDEX idx_application_entitlement_application_id ON application_entitlement (application_id);

CREATE TABLE platform_configuration (
    configuration_id    UUID         NOT NULL,
    configuration_code  VARCHAR(4)   NOT NULL,
    configuration_name  VARCHAR(100) NOT NULL,
    configuration_value JSONB        NOT NULL,
    description         VARCHAR(500),
    status              VARCHAR(30)  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL,
    created_by          VARCHAR(100) NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    updated_by          VARCHAR(100) NOT NULL,
    CONSTRAINT pk_platform_configuration PRIMARY KEY (configuration_id),
    CONSTRAINT uk_platform_configuration_configuration_code UNIQUE (configuration_code)
);
