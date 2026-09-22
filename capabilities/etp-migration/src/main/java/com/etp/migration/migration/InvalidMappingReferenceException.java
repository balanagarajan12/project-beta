package com.etp.migration.migration;

import java.util.UUID;

public class InvalidMappingReferenceException extends RuntimeException {

	public InvalidMappingReferenceException(UUID mappingId) {
		super("Migration mapping not found: " + mappingId);
	}
}
