package com.etp.migration.processing;

import com.etp.migration.entity.MigrationRecordStatus;
import java.util.List;

public record ProcessedRecord(
		long sourceRecordNumber,
		TargetRecord target,
		MigrationRecordStatus status,
		List<ProcessingError> errors
) {
}
