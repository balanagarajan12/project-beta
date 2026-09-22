package com.etp.migration.migration;

import java.util.UUID;

public class MigrationDefinitionNotFoundException extends RuntimeException {

	public MigrationDefinitionNotFoundException(UUID id) {
		super("Migration definition not found: " + id);
	}
}
