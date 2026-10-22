ALTER TABLE migration_run
    ADD COLUMN background_execution BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN execution_worker_id VARCHAR(200),
    ADD COLUMN execution_lease_until TIMESTAMP WITH TIME ZONE;

COMMENT ON COLUMN migration_run.background_execution IS
    'True when POST /execute accepted this run for background preparation and batch processing. A QUEUED run created by POST /runs stays false.';

COMMENT ON COLUMN migration_run.execution_lease_until IS
    'Claim lease for the background driver. Null or past means another worker may claim the run.';
