package com.etp.iam.authentication;

public class KeycloakTokenException extends RuntimeException {

	private final int statusCode;

	public KeycloakTokenException(String message, int statusCode) {
		super(message);
		this.statusCode = statusCode;
	}

	public int getStatusCode() {
		return statusCode;
	}
}
