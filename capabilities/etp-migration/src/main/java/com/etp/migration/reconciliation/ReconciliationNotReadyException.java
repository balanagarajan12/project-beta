package com.etp.migration.reconciliation;

import com.etp.migration.processing.MigrationErrorCodes;

public class ReconciliationNotReadyException extends RuntimeException {

	private final String errorCode = MigrationErrorCodes.RECONCILIATION_NOT_READY;

	public ReconciliationNotReadyException(String message) {
		super(message);
	}

	public String errorCode() {
		return errorCode;
	}
}
