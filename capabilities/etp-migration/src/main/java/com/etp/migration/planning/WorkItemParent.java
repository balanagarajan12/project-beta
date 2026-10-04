package com.etp.migration.planning;

/**
 * Direct parent of a work item. This is a source identity, not a target key.
 */
public record WorkItemParent(String entityName, String sourceIdentity) {
}
