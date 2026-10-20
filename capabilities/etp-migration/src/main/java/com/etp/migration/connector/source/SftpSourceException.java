package com.etp.migration.connector.source;

/**
 * Source acquisition failure for an SFTP CSV source. The message is safe to return from the API.
 */
public class SftpSourceException extends RuntimeException {

	private final String errorCode;

	public SftpSourceException(String errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}

	public SftpSourceException(String errorCode, String message, Throwable cause) {
		super(message, cause);
		this.errorCode = errorCode;
	}

	public String errorCode() {
		return errorCode;
	}
}
