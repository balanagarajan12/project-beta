package com.etp.iam.authorization;

public class AuthorizationException extends RuntimeException {

	private final Reason reason;

	public AuthorizationException(Reason reason, String message) {
		super(message);
		this.reason = reason;
	}

	public Reason getReason() {
		return reason;
	}

	public enum Reason {
		INVALID_IDENTITY,
		PRINCIPAL_NOT_FOUND,
		PRINCIPAL_INACTIVE
	}
}
