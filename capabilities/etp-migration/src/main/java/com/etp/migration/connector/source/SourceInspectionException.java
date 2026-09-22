package com.etp.migration.connector.source;

public class SourceInspectionException extends RuntimeException {

	public SourceInspectionException(String message) {
		super(message);
	}

	public SourceInspectionException(String message, Throwable cause) {
		super(message, cause);
	}
}
