-- Remembers generated target identities for a run so a retry uses the same id.
-- This is control-plane state. It is not a canonical business table.

CREATE TABLE migration_identity_allocation (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    migration_run_id    UUID         NOT NULL,
    entity_name         VARCHAR(200) NOT NULL,
    source_identity     VARCHAR(500) NOT NULL,
    target_identity     VARCHAR(500) NOT NULL,
    sequence_no         BIGINT       NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_identity_allocation_source UNIQUE (migration_run_id, entity_name, source_identity),
    CONSTRAINT uk_identity_allocation_sequence UNIQUE (migration_run_id, entity_name, sequence_no)
);

CREATE INDEX ix_identity_allocation_run_entity
    ON migration_identity_allocation (migration_run_id, entity_name);
