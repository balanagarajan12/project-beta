package com.etp.migration.entity;

public enum MigrationBatchStatus {
	PENDING,
	CLAIMED,
	PROCESSING,
	COMPLETED,
	COMPLETED_WITH_ERRORS,
	FAILED,
	RETRY_PENDING,
	CANCELLED
}
