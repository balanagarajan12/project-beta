package com.etp.migration.identity;

import java.util.List;

/**
 * A source identity with no usable target identity. No target id is invented.
 */
public record UnresolvedIdentity(
		EntityIdentity source,
		IdentityResolutionFailureKind kind,
		String message,
		List<ParentIdentityReference> parents
) {
	public UnresolvedIdentity {
		parents = parents == null ? List.of() : List.copyOf(parents);
	}
}
