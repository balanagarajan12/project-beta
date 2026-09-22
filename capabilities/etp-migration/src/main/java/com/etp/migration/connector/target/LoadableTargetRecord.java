package com.etp.migration.connector.target;

import com.etp.migration.processing.TargetRecord;

public record LoadableTargetRecord(
		long sourceRecordNumber,
		TargetRecord targetRecord,
		String idempotencyKey
) {
}
