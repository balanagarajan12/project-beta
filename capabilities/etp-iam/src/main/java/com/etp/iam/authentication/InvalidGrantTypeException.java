package com.etp.iam.authentication;

public class InvalidGrantTypeException extends RuntimeException {

	public InvalidGrantTypeException(String message) {
		super(message);
	}
}
