package com.etp.migration.connector.source;

public record SourceColumn(
		String name,
		String inferredType,
		int ordinalPosition,
		boolean nullable
) {
}
