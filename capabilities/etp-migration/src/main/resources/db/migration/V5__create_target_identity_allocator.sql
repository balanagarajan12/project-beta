-- Reserves numeric id ranges for generated target identities.
-- namespace is the configured prefix, so two prefixes never share a counter.
-- current_value is the last reserved number. It is runtime state, not configuration.
-- floor_established is set after existing target ids have been considered once.
-- Normal allocation updates current_value. It does not scan target tables.

CREATE TABLE target_identity_allocator (
    namespace           VARCHAR(200) PRIMARY KEY,
    current_value       BIGINT       NOT NULL,
    floor_established   BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
