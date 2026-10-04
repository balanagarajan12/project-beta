package com.etp.migration.scope;

/**
 * One entity identity inside a {@link LogicalMigrationScope}.
 * {@code sourceRecordNumber} is provenance. Identity is {@code entityName} plus {@code sourceIdentity}.
 * This type does not carry source field payloads.
 */
public record ScopedEntity(String entityName, String sourceIdentity, long sourceRecordNumber) {
}
