package com.etp.migration.mapping;

import java.util.List;

public record RelationshipDefinition(
		String parent,
		String child,
		RelationshipCardinality cardinality,
		List<RelationshipKey> keys,
		TargetForeignKey targetForeignKey
) {
	public RelationshipDefinition(
			String parent,
			String child,
			RelationshipCardinality cardinality,
			List<RelationshipKey> keys) {
		this(parent, child, cardinality, keys, null);
	}

	public RelationshipDefinition {
		keys = keys == null ? List.of() : List.copyOf(keys);
	}
}
