package com.etp.migration.migration;

import java.util.UUID;

public class MappingNotFoundException extends RuntimeException {

	public MappingNotFoundException(UUID mappingId) {
		super("Migration mapping not found: " + mappingId);
	}
}
