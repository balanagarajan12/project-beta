-- One open promotion per run. Finished and failed rows remain, so a later preparation
-- can create a new NOT_STARTED row after PROMOTION_FAILED or PARTIALLY_PROMOTED.
-- Acceptance inserts NOT_STARTED in the same transaction as the phase job. A second
-- open insert fails this index and rolls back with the losing job insert.

CREATE UNIQUE INDEX uk_migration_promotion_open
    ON migration_promotion (migration_run_id)
    WHERE status IN ('NOT_STARTED', 'READY', 'PROMOTING');
