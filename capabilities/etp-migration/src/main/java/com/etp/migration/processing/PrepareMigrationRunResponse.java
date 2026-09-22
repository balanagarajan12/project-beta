package com.etp.migration.processing;

import java.util.UUID;

public record PrepareMigrationRunResponse(
		UUID runId,
		String status,
		Long sourceRecordCount,
		Integer batchSize,
		Integer batchCount,
		String sourceChecksum,
		String statusMessage
) {
}
