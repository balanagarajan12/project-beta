package com.etp.migration.processing;

public class TransformationException extends RuntimeException {

	private final String errorCode;
	private final String fieldName;

	public TransformationException(String errorCode, String fieldName, String message) {
		super(message);
		this.errorCode = errorCode;
		this.fieldName = fieldName;
	}

	public TransformationException(String errorCode, String fieldName, String message, Throwable cause) {
		super(message, cause);
		this.errorCode = errorCode;
		this.fieldName = fieldName;
	}

	public String errorCode() {
		return errorCode;
	}

	public String fieldName() {
		return fieldName;
	}
}
