package com.etp.migration.identity;

/**
 * Source identity scoped by entity type. {@code Address:A001} and {@code Site:A001} are different keys.
 */
public record EntityIdentity(String entityName, String sourceIdentity) {
}
