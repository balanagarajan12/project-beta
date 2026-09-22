package com.etp.migration.connector.storage;

public class ArtifactNotFoundException extends RuntimeException {

	public ArtifactNotFoundException(String objectKey) {
		super("Source artifact not found: " + objectKey);
	}
}
