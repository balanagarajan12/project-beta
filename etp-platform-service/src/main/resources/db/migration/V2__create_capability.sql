CREATE TABLE capability (
    capability_id     UUID         NOT NULL,
    capability_code   VARCHAR(100) NOT NULL,
    capability_name   VARCHAR(200) NOT NULL,
    description       TEXT,
    category          VARCHAR(50),
    lifecycle_status  VARCHAR(30)  NOT NULL,
    criticality       VARCHAR(30),
    created_at        TIMESTAMPTZ  NOT NULL,
    created_by        VARCHAR(100) NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    updated_by        VARCHAR(100) NOT NULL,
    CONSTRAINT pk_capability PRIMARY KEY (capability_id),
    CONSTRAINT uk_capability_capability_code UNIQUE (capability_code)
);
