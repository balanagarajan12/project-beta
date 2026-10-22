package com.etp.migration.connector.target.uam;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A parent identity copied from an earlier batch is not proof that the parent row was committed.
 * Identifiers inserted in the current batch are treated as present. Every other referenced value
 * must be found in the target before the child insert.
 */
final class UamParentExistence {

	private UamParentExistence() {
	}

	static List<String> unresolved(Set<String> plannedParentIds, Collection<String> referencedIds) {
		Set<String> pending = new LinkedHashSet<>();
		for (String referenced : referencedIds) {
			if (referenced == null || referenced.isBlank()) {
				continue;
			}
			if (plannedParentIds == null || !plannedParentIds.contains(referenced)) {
				pending.add(referenced);
			}
		}
		return new ArrayList<>(pending);
	}
}
