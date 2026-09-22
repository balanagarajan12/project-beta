package com.etp.migration.connector.target;

public record TargetRecordError(
		long sourceRecordNumber,
		String errorCode,
		String errorType,
		String message,
		boolean retryable,
		Integer httpStatus
) {
}
