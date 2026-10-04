package com.etp.migration.identity;

/**
 * Reads the highest existing target id for one prefix. Implementations must not be called on every allocation.
 */
public interface TargetIdentityFloorProbe {

	long existingMaximum(String schema, String table, String idColumn, String prefix, int sequenceLength);
}
