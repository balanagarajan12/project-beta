package com.etp.migration.connector.target;

public class UnsupportedTargetTypeException extends RuntimeException {

	public UnsupportedTargetTypeException(String targetType) {
		super("Unsupported target type: " + targetType);
	}
}
