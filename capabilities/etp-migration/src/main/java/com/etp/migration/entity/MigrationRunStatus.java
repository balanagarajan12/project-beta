package com.etp.migration.entity;

public enum MigrationRunStatus {
	CREATED,
	QUEUED,
	RUNNING,
	PAUSED,
	COMPLETED,
	COMPLETED_WITH_ERRORS,
	FAILED,
	CANCEL_REQUESTED,
	CANCELLED
}
