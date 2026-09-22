package com.etp.migration.connector.target;

import com.etp.migration.entity.MigrationRecordStatus;

public record TargetReference(
		long sourceRecordNumber,
		String targetId,
		MigrationRecordStatus status
) {
}
