CREATE TABLE platform_version (
    platform_version_id UUID         NOT NULL,
    version             VARCHAR(50)  NOT NULL,
    status              VARCHAR(30)  NOT NULL,
    release_date        TIMESTAMPTZ,
    release_notes       TEXT,
    created_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_platform_version PRIMARY KEY (platform_version_id),
    CONSTRAINT uk_platform_version_version UNIQUE (version)
);
