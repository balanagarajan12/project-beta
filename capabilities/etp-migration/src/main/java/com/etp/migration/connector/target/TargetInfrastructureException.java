package com.etp.migration.connector.target;

public class TargetInfrastructureException extends RuntimeException {

	private final String errorCode;

	public TargetInfrastructureException(String errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}

	public TargetInfrastructureException(String errorCode, String message, Throwable cause) {
		super(message, cause);
		this.errorCode = errorCode;
	}

	public String errorCode() {
		return errorCode;
	}
}
