package com.etp.migration.migration;

import java.util.UUID;

public class MigrationSourceNotFoundException extends RuntimeException {

	public MigrationSourceNotFoundException(UUID sourceId) {
		super("Migration source not found: " + sourceId);
	}

	public MigrationSourceNotFoundException(String message) {
		super(message);
	}
}
