package com.etp.migration.entity;

public enum MigrationRecordStatus {
	PROCESSED,
	SUCCESS,
	DUPLICATE,
	VALIDATION_FAILED,
	ENRICHMENT_FAILED,
	LOAD_FAILED,
	REJECTED
}
