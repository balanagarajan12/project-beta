package com.etp.migration.reconciliation.dto;

public record ReconciliationBundleResponse(
		ReconciliationItemResponse count,
		ReconciliationItemResponse checksum,
		ReconciliationItemResponse target,
		ReconciliationItemResponse full
) {
}
