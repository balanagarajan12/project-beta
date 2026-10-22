-- Background reconciliation, promotion preparation, and promotion.
-- Job status is separate from the reconciliation outcome and the promotion status.

CREATE TABLE migration_phase_job (
    id                 UUID PRIMARY KEY,
    migration_run_id   UUID NOT NULL,
    phase              VARCHAR(40) NOT NULL,
    status             VARCHAR(20) NOT NULL,
    scope              VARCHAR(40),
    promotion_id       UUID,
    worker_id          VARCHAR(200),
    lease_until        TIMESTAMP WITH TIME ZONE,
    attempt_count      INTEGER NOT NULL DEFAULT 0,
    outcome            VARCHAR(40),
    error_code         VARCHAR(100),
    error_message      VARCHAR(2000),
    requested_by       VARCHAR(100),
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at         TIMESTAMP WITH TIME ZONE,
    progress_at        TIMESTAMP WITH TIME ZONE,
    completed_at       TIMESTAMP WITH TIME ZONE,
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_phase_job_run
        FOREIGN KEY (migration_run_id) REFERENCES migration_run(id),
    CONSTRAINT ck_phase_job_phase
        CHECK (phase IN ('RECONCILIATION', 'PROMOTION_PREPARATION', 'PROMOTION')),
    CONSTRAINT ck_phase_job_status
        CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_phase_job_attempt
        CHECK (attempt_count >= 0)
);

CREATE INDEX idx_phase_job_run_phase
    ON migration_phase_job (migration_run_id, phase, created_at DESC);

CREATE INDEX idx_phase_job_recoverable
    ON migration_phase_job (status, lease_until);

CREATE UNIQUE INDEX uk_phase_job_active
    ON migration_phase_job (migration_run_id, phase, COALESCE(scope, ''))
    WHERE status IN ('QUEUED', 'RUNNING');
