package com.etp.migration.identity;

/**
 * Reads the highest existing target id for one prefix. Implementations must not be called on every allocation.
 */
import javax.sql.DataSource;

public interface TargetIdentityFloorProbe {

	long existingMaximum(String schema, String table, String idColumn, String prefix, int sequenceLength);

	default long existingMaximum(
			String schema,
			String table,
			String idColumn,
			String prefix,
			int sequenceLength,
			DataSource dataSource) {
		return existingMaximum(schema, table, idColumn, prefix, sequenceLength);
	}
}
