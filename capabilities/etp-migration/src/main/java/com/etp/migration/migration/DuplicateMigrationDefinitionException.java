package com.etp.migration.migration;

public class DuplicateMigrationDefinitionException extends RuntimeException {

	public DuplicateMigrationDefinitionException(String name, String version) {
		super("Migration definition already exists for name '%s' and version '%s'".formatted(name, version));
	}
}
