package com.etp.migration.connector.source;

public class UnsupportedSourceTypeException extends RuntimeException {

	public UnsupportedSourceTypeException(String message) {
		super(message);
	}
}
