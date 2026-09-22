package com.etp.migration.reconciliation.dto;

import com.etp.migration.entity.ReconciliationStatus;
import java.util.UUID;

public record MigrationReconciliationResponse(
		UUID runId,
		ReconciliationStatus status,
		ReconciliationBundleResponse reconciliation
) {
}
