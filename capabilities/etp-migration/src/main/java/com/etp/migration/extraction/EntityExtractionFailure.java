package com.etp.migration.extraction;

public record EntityExtractionFailure(
		long sourceRecordNumber,
		String entityName,
		String sourceKey,
		String message
) {
}
