package com.etp.migration.planning;

import java.util.List;

/**
 * One entity instance to process later. Fields are identity and provenance only.
 * {@code rootEntity} and {@code rootIdentity} keep the logical scope. {@code parents} are direct source dependencies.
 */
public record ExecutionWorkItem(
		String entityName,
		String sourceIdentity,
		long sourceRecordNumber,
		String rootEntity,
		String rootIdentity,
		List<WorkItemParent> parents
) {
	public ExecutionWorkItem {
		parents = parents == null ? List.of() : List.copyOf(parents);
	}
}
