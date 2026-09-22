package com.etp.migration.connector.source;

import java.util.List;
import java.util.Map;

public record SourceInspection(
		long recordCount,
		List<SourceColumn> columns,
		String checksum,
		long sizeBytes,
		String sourceFormat,
		Map<String, Object> metadata
) {
}
