package com.etp.migration.identity;

import java.util.List;

/**
 * A source identity that has a target identity. Source identity is kept.
 * {@code targetEntity} and {@code targetKey} come from configuration and are not assumed to match the source key.
 * This type does not carry a source payload and does not load a target row.
 */
public record ResolvedIdentity(
		EntityIdentity source,
		String targetIdentity,
		String targetEntity,
		String sourceKey,
		String targetKey,
		List<ParentIdentityReference> parents
) {
	public ResolvedIdentity {
		parents = parents == null ? List.of() : List.copyOf(parents);
	}
}
