package com.etp.migration.migration;

public class DuplicateMigrationMappingException extends RuntimeException {

	public DuplicateMigrationMappingException(String name, String version) {
		super("Migration mapping already exists for name '%s' and version '%s'".formatted(name, version));
	}
}
