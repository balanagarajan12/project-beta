-- ============================================================
-- ETP Migration Capability
-- Database : etp_migration
-- Version  : V1
-- Purpose  : Migration control plane for large-scale migration
--            runs up to 30M+ records.
-- ============================================================

-- ============================================================
-- 1. MIGRATION DEFINITION
-- ============================================================

CREATE TABLE migration_definition (
    id                  UUID PRIMARY KEY,

    name                VARCHAR(200) NOT NULL,
    version             VARCHAR(50)  NOT NULL,

    description         VARCHAR(1000),

    source_type         VARCHAR(50)  NOT NULL,
    target_type         VARCHAR(50)  NOT NULL,
    target_entity       VARCHAR(200),

    mapping_id          UUID,

    batch_size          INTEGER NOT NULL DEFAULT 10000,

    load_mode           VARCHAR(30) NOT NULL DEFAULT 'BULK',

    status              VARCHAR(30) NOT NULL DEFAULT 'DRAFT',

    configuration       JSONB NOT NULL DEFAULT '{}'::jsonb,

    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          VARCHAR(100),

    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by          VARCHAR(100),

    CONSTRAINT uk_migration_definition_name_version
        UNIQUE (name, version),

    CONSTRAINT ck_migration_definition_batch_size
        CHECK (batch_size > 0),

    CONSTRAINT ck_migration_definition_load_mode
        CHECK (load_mode IN (
            'SINGLE',
            'BATCH',
            'BULK'
        )),

    CONSTRAINT ck_migration_definition_status
        CHECK (status IN (
            'DRAFT',
            'ACTIVE',
            'INACTIVE'
        ))
);

CREATE INDEX idx_migration_definition_status
    ON migration_definition(status);


-- ============================================================
-- 2. MIGRATION SOURCE
-- ============================================================

CREATE TABLE migration_source (
    id                  UUID PRIMARY KEY,

    migration_definition_id UUID NOT NULL,

    name                VARCHAR(200) NOT NULL,
    source_type         VARCHAR(50) NOT NULL,

    configuration       JSONB NOT NULL DEFAULT '{}'::jsonb,

    enabled             BOOLEAN NOT NULL DEFAULT TRUE,

    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          VARCHAR(100),

    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by          VARCHAR(100),

    CONSTRAINT fk_migration_source_definition
        FOREIGN KEY (migration_definition_id)
        REFERENCES migration_definition(id)
        ON DELETE CASCADE
);

CREATE INDEX idx_migration_source_definition
    ON migration_source(migration_definition_id);

CREATE INDEX idx_migration_source_enabled
    ON migration_source(enabled);


-- ============================================================
-- 3. MIGRATION MAPPING
-- ============================================================

CREATE TABLE migration_mapping (
    id                  UUID PRIMARY KEY,

    name                VARCHAR(200) NOT NULL,
    version             VARCHAR(50) NOT NULL,

    source_type         VARCHAR(100) NOT NULL,
    target_type         VARCHAR(100) NOT NULL,

    description         VARCHAR(1000),

    configuration       JSONB NOT NULL DEFAULT '{}'::jsonb,

    status              VARCHAR(30) NOT NULL DEFAULT 'DRAFT',

    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          VARCHAR(100),

    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by          VARCHAR(100),

    CONSTRAINT uk_migration_mapping_name_version
        UNIQUE (name, version),

    CONSTRAINT ck_migration_mapping_status
        CHECK (status IN (
            'DRAFT',
            'ACTIVE',
            'INACTIVE'
        ))
);

CREATE INDEX idx_migration_mapping_status
    ON migration_mapping(status);


-- ============================================================
-- 4. MIGRATION MAPPING RULE
-- ============================================================

CREATE TABLE migration_mapping_rule (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    mapping_id          UUID NOT NULL,

    sequence_no         INTEGER NOT NULL,

    source_field        VARCHAR(500),
    target_field        VARCHAR(500) NOT NULL,

    transformation_type VARCHAR(50) NOT NULL,

    transformation_expression TEXT,

    default_value       TEXT,

    configuration       JSONB NOT NULL DEFAULT '{}'::jsonb,

    mandatory           BOOLEAN NOT NULL DEFAULT FALSE,

    enabled             BOOLEAN NOT NULL DEFAULT TRUE,

    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_mapping_rule_mapping
        FOREIGN KEY (mapping_id)
        REFERENCES migration_mapping(id)
        ON DELETE CASCADE,

    CONSTRAINT uk_mapping_rule_sequence
        UNIQUE (mapping_id, sequence_no),

    CONSTRAINT ck_mapping_rule_sequence
        CHECK (sequence_no > 0),

    CONSTRAINT ck_mapping_rule_transformation_type
        CHECK (transformation_type IN (
            'DIRECT',
            'TRIM',
            'UPPERCASE',
            'LOWERCASE',
            'DEFAULT',
            'CONCAT',
            'SPLIT',
            'LOOKUP',
            'REGEX',
            'CONDITIONAL',
            'DATE_FORMAT',
            'CUSTOM'
        ))
);

CREATE INDEX idx_mapping_rule_mapping
    ON migration_mapping_rule(mapping_id);


-- ============================================================
-- 5. MIGRATION RUN
-- ============================================================

CREATE TABLE migration_run (
    id                  UUID PRIMARY KEY,

    migration_definition_id UUID NOT NULL,

    run_number          BIGINT NOT NULL,

    idempotency_key     VARCHAR(300),

    status              VARCHAR(30) NOT NULL DEFAULT 'CREATED',

    started_at          TIMESTAMP WITH TIME ZONE,
    completed_at        TIMESTAMP WITH TIME ZONE,

    requested_by        VARCHAR(100),

    source_snapshot     JSONB NOT NULL DEFAULT '{}'::jsonb,
    configuration_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,

    -- Aggregate counters.
    -- Updated at batch completion rather than per record.
    total_records       BIGINT NOT NULL DEFAULT 0,
    processed_records   BIGINT NOT NULL DEFAULT 0,
    success_records     BIGINT NOT NULL DEFAULT 0,
    duplicate_records   BIGINT NOT NULL DEFAULT 0,
    validation_failures BIGINT NOT NULL DEFAULT 0,
    enrichment_failures BIGINT NOT NULL DEFAULT 0,
    load_failures       BIGINT NOT NULL DEFAULT 0,
    rejected_records    BIGINT NOT NULL DEFAULT 0,

    total_batches       INTEGER NOT NULL DEFAULT 0,
    completed_batches   INTEGER NOT NULL DEFAULT 0,
    failed_batches      INTEGER NOT NULL DEFAULT 0,

    error_message       VARCHAR(2000),

    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_migration_run_definition
        FOREIGN KEY (migration_definition_id)
        REFERENCES migration_definition(id),

    CONSTRAINT uk_migration_run_number
        UNIQUE (migration_definition_id, run_number),

    CONSTRAINT uk_migration_run_idempotency
        UNIQUE (migration_definition_id, idempotency_key),

    CONSTRAINT ck_migration_run_status
        CHECK (status IN (
            'CREATED',
            'QUEUED',
            'RUNNING',
            'PAUSED',
            'COMPLETED',
            'COMPLETED_WITH_ERRORS',
            'FAILED',
            'CANCEL_REQUESTED',
            'CANCELLED'
        )),

    CONSTRAINT ck_migration_run_counts
        CHECK (
            total_records >= 0
            AND processed_records >= 0
            AND success_records >= 0
            AND duplicate_records >= 0
            AND validation_failures >= 0
            AND enrichment_failures >= 0
            AND load_failures >= 0
            AND rejected_records >= 0
        )
);

CREATE INDEX idx_migration_run_definition
    ON migration_run(migration_definition_id);

CREATE INDEX idx_migration_run_status
    ON migration_run(status);

CREATE INDEX idx_migration_run_created_at
    ON migration_run(created_at DESC);

CREATE INDEX idx_migration_run_running
    ON migration_run(status, started_at);


-- ============================================================
-- 6. MIGRATION BATCH
-- ============================================================

CREATE TABLE migration_batch (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    migration_run_id    UUID NOT NULL,

    batch_number        INTEGER NOT NULL,

    status              VARCHAR(30) NOT NULL DEFAULT 'PENDING',

    -- Generic source location.
    -- Example:
    -- {
    --   "objectKey": "raw/run-123/source.csv",
    --   "partition": 15,
    --   "startOffset": 100000,
    --   "endOffset": 149999
    -- }
    source_locator      JSONB NOT NULL DEFAULT '{}'::jsonb,

    source_checksum     VARCHAR(128),

    record_count        INTEGER NOT NULL DEFAULT 0,

    processed_count     INTEGER NOT NULL DEFAULT 0,
    success_count       INTEGER NOT NULL DEFAULT 0,
    duplicate_count     INTEGER NOT NULL DEFAULT 0,
    validation_failure_count INTEGER NOT NULL DEFAULT 0,
    enrichment_failure_count INTEGER NOT NULL DEFAULT 0,
    load_failure_count  INTEGER NOT NULL DEFAULT 0,
    rejected_count      INTEGER NOT NULL DEFAULT 0,

    attempt_count       INTEGER NOT NULL DEFAULT 0,

    worker_id           VARCHAR(200),

    claimed_at          TIMESTAMP WITH TIME ZONE,
    lease_until         TIMESTAMP WITH TIME ZONE,

    next_attempt_at     TIMESTAMP WITH TIME ZONE,

    started_at          TIMESTAMP WITH TIME ZONE,
    completed_at        TIMESTAMP WITH TIME ZONE,

    error_message       VARCHAR(2000),

    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_migration_batch_run
        FOREIGN KEY (migration_run_id)
        REFERENCES migration_run(id)
        ON DELETE CASCADE,

    CONSTRAINT uk_migration_batch_run_number
        UNIQUE (migration_run_id, batch_number),

    CONSTRAINT ck_migration_batch_number
        CHECK (batch_number > 0),

    CONSTRAINT ck_migration_batch_status
        CHECK (status IN (
            'PENDING',
            'CLAIMED',
            'PROCESSING',
            'COMPLETED',
            'COMPLETED_WITH_ERRORS',
            'FAILED',
            'RETRY_PENDING',
            'CANCELLED'
        )),

    CONSTRAINT ck_migration_batch_counts
        CHECK (
            record_count >= 0
            AND processed_count >= 0
            AND success_count >= 0
            AND duplicate_count >= 0
            AND validation_failure_count >= 0
            AND enrichment_failure_count >= 0
            AND load_failure_count >= 0
        )
);

CREATE INDEX idx_migration_batch_run
    ON migration_batch(migration_run_id);

CREATE INDEX idx_migration_batch_status
    ON migration_batch(status);

CREATE INDEX idx_migration_batch_queue
    ON migration_batch(status, next_attempt_at, lease_until);

CREATE INDEX idx_migration_batch_worker
    ON migration_batch(worker_id);


-- ============================================================
-- 7. MIGRATION ARTIFACT
-- ============================================================

-- Metadata for files stored in MinIO / S3 / Azure Blob etc.
-- The actual large data is NOT stored in PostgreSQL.

CREATE TABLE migration_artifact (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    migration_run_id    UUID NOT NULL,
    migration_batch_id  BIGINT,

    artifact_type       VARCHAR(50) NOT NULL,

    storage_provider    VARCHAR(50) NOT NULL,
    object_key          VARCHAR(2000) NOT NULL,

    file_format         VARCHAR(30) NOT NULL,

    file_size_bytes     BIGINT,
    record_count        BIGINT,

    checksum_algorithm  VARCHAR(30),
    checksum            VARCHAR(256),

    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_artifact_run
        FOREIGN KEY (migration_run_id)
        REFERENCES migration_run(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_artifact_batch
        FOREIGN KEY (migration_batch_id)
        REFERENCES migration_batch(id)
        ON DELETE CASCADE,

    CONSTRAINT ck_artifact_type
        CHECK (artifact_type IN (
            'RAW_SOURCE',
            'STANDARDIZED',
            'TRANSFORMED',
            'MANIFEST',
            'REJECTED',
            'ERROR',
            'RECONCILIATION'
        )),

    CONSTRAINT ck_artifact_provider
        CHECK (storage_provider IN (
            'MINIO',
            'S3',
            'AZURE_BLOB',
            'GCS',
            'FILE_SYSTEM'
        )),

    CONSTRAINT ck_artifact_format
        CHECK (file_format IN (
            'CSV',
            'JSON',
            'JSONL',
            'PARQUET',
            'AVRO',
            'TEXT',
            'ZIP'
        ))
);

CREATE INDEX idx_migration_artifact_run
    ON migration_artifact(migration_run_id);

CREATE INDEX idx_migration_artifact_batch
    ON migration_artifact(migration_batch_id);

CREATE INDEX idx_migration_artifact_type
    ON migration_artifact(artifact_type);


-- ============================================================
-- 8. MIGRATION ERROR
-- ============================================================

CREATE TABLE migration_error (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    migration_run_id    UUID NOT NULL,
    migration_batch_id  BIGINT,

    stage               VARCHAR(50) NOT NULL,

    error_code          VARCHAR(100),
    error_type          VARCHAR(100),

    source_record_key   VARCHAR(500),
    source_record_hash  VARCHAR(128),

    target_entity_key   VARCHAR(500),

    field_name          VARCHAR(500),

    error_message       VARCHAR(2000) NOT NULL,

    error_details       JSONB NOT NULL DEFAULT '{}'::jsonb,

    retryable           BOOLEAN NOT NULL DEFAULT FALSE,

    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_migration_error_run
        FOREIGN KEY (migration_run_id)
        REFERENCES migration_run(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_migration_error_batch
        FOREIGN KEY (migration_batch_id)
        REFERENCES migration_batch(id)
        ON DELETE CASCADE,

    CONSTRAINT ck_migration_error_stage
        CHECK (stage IN (
            'EXTRACT',
            'STANDARDIZE',
            'TRANSFORM',
            'ENRICH',
            'VALIDATE',
            'DEDUPLICATE',
            'LOAD',
            'RECONCILE'
        ))
);

CREATE INDEX idx_migration_error_run
    ON migration_error(migration_run_id);

CREATE INDEX idx_migration_error_batch
    ON migration_error(migration_batch_id);

CREATE INDEX idx_migration_error_stage
    ON migration_error(migration_run_id, stage);

CREATE INDEX idx_migration_error_source_key
    ON migration_error(migration_run_id, source_record_key);


-- ============================================================
-- 9. MIGRATION RECONCILIATION
-- ============================================================

CREATE TABLE migration_reconciliation (
    id                  UUID PRIMARY KEY,

    migration_run_id    UUID NOT NULL,

    reconciliation_type VARCHAR(50) NOT NULL,

    source_count        BIGINT NOT NULL DEFAULT 0,
    processed_count     BIGINT NOT NULL DEFAULT 0,
    success_count       BIGINT NOT NULL DEFAULT 0,
    rejected_count      BIGINT NOT NULL DEFAULT 0,
    duplicate_count     BIGINT NOT NULL DEFAULT 0,
    failure_count       BIGINT NOT NULL DEFAULT 0,

    difference_count    BIGINT NOT NULL DEFAULT 0,

    status              VARCHAR(30) NOT NULL DEFAULT 'PENDING',

    details             JSONB NOT NULL DEFAULT '{}'::jsonb,

    started_at          TIMESTAMP WITH TIME ZONE,
    completed_at        TIMESTAMP WITH TIME ZONE,

    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_reconciliation_run
        FOREIGN KEY (migration_run_id)
        REFERENCES migration_run(id)
        ON DELETE CASCADE,

    CONSTRAINT uk_reconciliation_run_type
        UNIQUE (migration_run_id, reconciliation_type),

    CONSTRAINT ck_reconciliation_type
        CHECK (reconciliation_type IN (
            'COUNT',
            'CHECKSUM',
            'TARGET',
            'FULL'
        )),

    CONSTRAINT ck_reconciliation_status
        CHECK (status IN (
            'PENDING',
            'RUNNING',
            'PASSED',
            'PASSED_WITH_WARNINGS',
            'FAILED'
        ))
);

CREATE INDEX idx_reconciliation_run
    ON migration_reconciliation(migration_run_id);


-- ============================================================
-- 10. OPTIONAL RECORD-LEVEL TRACKING
-- ============================================================
--
-- IMPORTANT:
-- This table is NOT intended to receive 30M successful rows
-- by default.
--
-- Use it for:
--   - selected-record tracking
--   - failed records
--   - records requiring investigation
--   - controlled audit scenarios
--
-- Successful-record lineage should normally be written to the
-- batch manifest in object storage.
-- ============================================================

CREATE TABLE migration_record (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    migration_run_id    UUID NOT NULL,
    migration_batch_id  BIGINT NOT NULL,

    source_record_key   VARCHAR(500),
    source_record_hash  VARCHAR(128),

    target_entity_key   VARCHAR(500),

    status              VARCHAR(30) NOT NULL,

    error_id            BIGINT,

    processed_at        TIMESTAMP WITH TIME ZONE,

    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_migration_record_run
        FOREIGN KEY (migration_run_id)
        REFERENCES migration_run(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_migration_record_batch
        FOREIGN KEY (migration_batch_id)
        REFERENCES migration_batch(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_migration_record_error
        FOREIGN KEY (error_id)
        REFERENCES migration_error(id)
        ON DELETE SET NULL,

    CONSTRAINT ck_migration_record_status
        CHECK (status IN (
            'PROCESSED',
            'SUCCESS',
            'DUPLICATE',
            'VALIDATION_FAILED',
            'ENRICHMENT_FAILED',
            'LOAD_FAILED',
            'REJECTED'
        ))
);

CREATE INDEX idx_migration_record_run
    ON migration_record(migration_run_id);

CREATE INDEX idx_migration_record_batch
    ON migration_record(migration_batch_id);

CREATE INDEX idx_migration_record_source_key
    ON migration_record(migration_run_id, source_record_key);

CREATE INDEX idx_migration_record_status
    ON migration_record(migration_run_id, status);


-- ============================================================
-- FOREIGN KEY: definition -> mapping
-- ============================================================

ALTER TABLE migration_definition
    ADD CONSTRAINT fk_migration_definition_mapping
    FOREIGN KEY (mapping_id)
    REFERENCES migration_mapping(id);


-- ============================================================
-- COMMENTS
-- ============================================================

COMMENT ON TABLE migration_definition IS
    'Reusable migration configuration and processing definition.';

COMMENT ON TABLE migration_source IS
    'Source system/file configuration for a migration definition.';

COMMENT ON TABLE migration_mapping IS
    'Metadata-driven source-to-target mapping definition.';

COMMENT ON TABLE migration_mapping_rule IS
    'Individual field transformation rules.';

COMMENT ON TABLE migration_run IS
    'Control-plane record representing one execution of a migration.';

COMMENT ON TABLE migration_batch IS
    'Restartable processing unit within a migration run.';

COMMENT ON TABLE migration_artifact IS
    'Metadata and object-storage references for migration artifacts.';

COMMENT ON TABLE migration_error IS
    'Record-level or batch-level migration errors and exceptions.';

COMMENT ON TABLE migration_reconciliation IS
    'Run-level reconciliation results.';

COMMENT ON TABLE migration_record IS
    'Optional record-level tracking. Do not populate successful rows by default for very large migrations.';
