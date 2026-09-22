-- ============================================================
-- PHASE 13A: PROMOTION CONTROL PLANE
-- ============================================================
-- Promotion is a separate lifecycle from migration execution.
-- MigrationRunStatus and MigrationBatchStatus are unchanged:
-- COMPLETED / COMPLETED_WITH_ERRORS still mean staging load
-- completed, not that application tables were promoted.
-- ============================================================

CREATE TABLE migration_promotion (
    id                    UUID PRIMARY KEY,

    migration_run_id      UUID NOT NULL,

    status                VARCHAR(30) NOT NULL DEFAULT 'NOT_STARTED',

    requested_by          VARCHAR(100),
    requested_at          TIMESTAMP WITH TIME ZONE,
    started_at            TIMESTAMP WITH TIME ZONE,
    completed_at          TIMESTAMP WITH TIME ZONE,

    total_batches         INTEGER NOT NULL DEFAULT 0,
    eligible_batches      INTEGER NOT NULL DEFAULT 0,
    promoted_batches      INTEGER NOT NULL DEFAULT 0,
    failed_batches        INTEGER NOT NULL DEFAULT 0,
    skipped_batches       INTEGER NOT NULL DEFAULT 0,

    total_records         BIGINT NOT NULL DEFAULT 0,
    promoted_records      BIGINT NOT NULL DEFAULT 0,
    failed_records        BIGINT NOT NULL DEFAULT 0,
    skipped_records       BIGINT NOT NULL DEFAULT 0,

    error_code            VARCHAR(100),
    error_message         VARCHAR(2000),

    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_migration_promotion_run
        FOREIGN KEY (migration_run_id)
        REFERENCES migration_run(id)
        ON DELETE CASCADE,

    CONSTRAINT ck_migration_promotion_status
        CHECK (status IN (
            'NOT_STARTED',
            'READY',
            'PROMOTING',
            'PROMOTED',
            'PARTIALLY_PROMOTED',
            'PROMOTION_FAILED'
        )),

    CONSTRAINT ck_migration_promotion_counts
        CHECK (
            total_batches >= 0
            AND eligible_batches >= 0
            AND promoted_batches >= 0
            AND failed_batches >= 0
            AND skipped_batches >= 0
            AND total_records >= 0
            AND promoted_records >= 0
            AND failed_records >= 0
            AND skipped_records >= 0
        )
);

CREATE INDEX idx_migration_promotion_run
    ON migration_promotion(migration_run_id);

CREATE INDEX idx_migration_promotion_status
    ON migration_promotion(status);


CREATE TABLE migration_promotion_batch (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    promotion_id            UUID NOT NULL,
    migration_run_id        UUID NOT NULL,
    migration_batch_id      BIGINT NOT NULL,

    status                  VARCHAR(30) NOT NULL DEFAULT 'NOT_ELIGIBLE',

    eligible_record_count   BIGINT NOT NULL DEFAULT 0,
    promoted_record_count   BIGINT NOT NULL DEFAULT 0,
    failed_record_count     BIGINT NOT NULL DEFAULT 0,
    skipped_record_count    BIGINT NOT NULL DEFAULT 0,

    started_at              TIMESTAMP WITH TIME ZONE,
    completed_at            TIMESTAMP WITH TIME ZONE,

    error_code              VARCHAR(100),
    error_message           VARCHAR(2000),

    created_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_migration_promotion_batch_promotion
        FOREIGN KEY (promotion_id)
        REFERENCES migration_promotion(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_migration_promotion_batch_run
        FOREIGN KEY (migration_run_id)
        REFERENCES migration_run(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_migration_promotion_batch_batch
        FOREIGN KEY (migration_batch_id)
        REFERENCES migration_batch(id)
        ON DELETE CASCADE,

    CONSTRAINT uk_migration_promotion_batch_promotion_batch
        UNIQUE (promotion_id, migration_batch_id),

    CONSTRAINT ck_migration_promotion_batch_status
        CHECK (status IN (
            'NOT_ELIGIBLE',
            'ELIGIBLE',
            'PROMOTING',
            'PROMOTED',
            'FAILED'
        )),

    CONSTRAINT ck_migration_promotion_batch_counts
        CHECK (
            eligible_record_count >= 0
            AND promoted_record_count >= 0
            AND failed_record_count >= 0
            AND skipped_record_count >= 0
        )
);

CREATE INDEX idx_migration_promotion_batch_promotion
    ON migration_promotion_batch(promotion_id);

CREATE INDEX idx_migration_promotion_batch_run
    ON migration_promotion_batch(migration_run_id);

CREATE INDEX idx_migration_promotion_batch_batch
    ON migration_promotion_batch(migration_batch_id);
