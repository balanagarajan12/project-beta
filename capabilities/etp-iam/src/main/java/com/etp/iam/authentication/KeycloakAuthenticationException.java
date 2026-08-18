package com.etp.iam.authentication;

public class KeycloakAuthenticationException extends RuntimeException {

	private final String error;

	public KeycloakAuthenticationException(String message, String error) {
		super(message);
		this.error = error;
	}

	public String getError() {
		return error;
	}
}
