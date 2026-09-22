package com.etp.migration.processing;

public class BatchProcessingException extends RuntimeException {

	private final String errorCode;

	public BatchProcessingException(String errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}

	public BatchProcessingException(String errorCode, String message, Throwable cause) {
		super(message, cause);
		this.errorCode = errorCode;
	}

	public String errorCode() {
		return errorCode;
	}
}
