-- Promotion failures are detailed in migration_error and summarized on the promotion batch
-- or phase job. PROMOTE is the stage for promotion execution and promotion preparation.
-- Reconciliation phase failures continue to use RECONCILE.

ALTER TABLE migration_error DROP CONSTRAINT ck_migration_error_stage;

ALTER TABLE migration_error ADD CONSTRAINT ck_migration_error_stage
    CHECK (stage IN (
        'EXTRACT',
        'STANDARDIZE',
        'TRANSFORM',
        'ENRICH',
        'VALIDATE',
        'DEDUPLICATE',
        'LOAD',
        'RECONCILE',
        'PROMOTE'
    ));
