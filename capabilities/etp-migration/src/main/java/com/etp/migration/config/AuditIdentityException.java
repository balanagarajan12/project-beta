package com.etp.migration.config;

import com.etp.migration.processing.MigrationErrorCodes;

public class AuditIdentityException extends RuntimeException {

	public AuditIdentityException(String message) {
		super(message);
	}

	public String errorCode() {
		return MigrationErrorCodes.AUDIT_IDENTITY_INVALID;
	}
}
