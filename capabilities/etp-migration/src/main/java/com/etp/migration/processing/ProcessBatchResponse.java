package com.etp.migration.processing;

import java.util.UUID;

public record ProcessBatchResponse(
		UUID runId,
		Long batchId,
		Integer batchNumber,
		String status,
		Integer totalRecords,
		Integer successRecords,
		Integer failedRecords,
		Integer rejectedRecords
) {
}
