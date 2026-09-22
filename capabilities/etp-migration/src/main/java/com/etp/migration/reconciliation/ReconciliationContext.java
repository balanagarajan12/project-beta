package com.etp.migration.reconciliation;

import com.etp.migration.connector.target.TargetVerification;
import com.etp.migration.entity.MigrationRun;
import java.util.Map;

public record ReconciliationContext(
		MigrationRun run,
		long sourceCount,
		long processedCount,
		long successCount,
		long rejectedCount,
		long duplicateCount,
		long failureCount,
		String sourceChecksum,
		String checksumAlgorithm,
		TargetVerification targetVerification
) {
	public long accountedCount() {
		return successCount + rejectedCount + duplicateCount + failureCount;
	}

	public Map<String, Object> sourceSnapshot() {
		return run.getSourceSnapshot();
	}
}
