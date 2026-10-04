package com.etp.migration.mapping;

import java.util.List;

public record RelationshipDefinition(
		String parent,
		String child,
		RelationshipCardinality cardinality,
		List<RelationshipKey> keys
) {
	public RelationshipDefinition {
		keys = keys == null ? List.of() : List.copyOf(keys);
	}
}
