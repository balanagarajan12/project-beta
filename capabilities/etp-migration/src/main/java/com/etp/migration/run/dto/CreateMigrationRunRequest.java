package com.etp.migration.run.dto;

import java.util.Map;

public record CreateMigrationRunRequest(
		String idempotencyKey,
		Map<String, Object> sourceSnapshot,
		Map<String, Object> configurationSnapshot
) {
}
