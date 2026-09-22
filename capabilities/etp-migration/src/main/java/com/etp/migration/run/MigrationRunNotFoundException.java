package com.etp.migration.run;

import java.util.UUID;

public class MigrationRunNotFoundException extends RuntimeException {

	public MigrationRunNotFoundException(UUID runId) {
		super("Migration run not found: " + runId);
	}
}
