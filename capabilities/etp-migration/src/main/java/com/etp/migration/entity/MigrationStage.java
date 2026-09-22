package com.etp.migration.entity;

public enum MigrationStage {
	EXTRACT,
	STANDARDIZE,
	TRANSFORM,
	ENRICH,
	VALIDATE,
	DEDUPLICATE,
	LOAD,
	RECONCILE
}
