-- Control state is independent of lifecycle status. A paused execution stays paused across restart.

ALTER TABLE migration_run
    ADD COLUMN control_state VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
    ADD COLUMN control_updated_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN last_control_action VARCHAR(20);

ALTER TABLE migration_run
    ADD CONSTRAINT ck_migration_run_control_state
        CHECK (control_state IN ('RUNNING', 'PAUSE_REQUESTED', 'PAUSED', 'RESUMING'));

ALTER TABLE migration_promotion
    ADD COLUMN control_state VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
    ADD COLUMN control_updated_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN last_control_action VARCHAR(20);

ALTER TABLE migration_promotion
    ADD CONSTRAINT ck_migration_promotion_control_state
        CHECK (control_state IN ('RUNNING', 'PAUSE_REQUESTED', 'PAUSED', 'RESUMING'));
