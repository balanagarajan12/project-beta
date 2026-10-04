-- Staging table used by PostgreSqlTargetConnector COPY.
-- Entity loads are unique per run and idempotency key.

CREATE SCHEMA IF NOT EXISTS migration_staging;

CREATE TABLE IF NOT EXISTS migration_staging.uam_entity_stage (
    migration_run_id UUID NOT NULL,
    migration_batch_id BIGINT NOT NULL,
    source_record_number BIGINT NOT NULL,
    target_payload JSONB NOT NULL,
    idempotency_key VARCHAR(500),
    loaded_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_uam_entity_stage_run_identity
    ON migration_staging.uam_entity_stage (migration_run_id, idempotency_key);
