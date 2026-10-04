package com.etp.migration.identity;

import java.util.List;

/**
 * Identity resolution for one execution batch. This is not a target load.
 */
public record IdentityResolutionResult(
		List<ResolvedIdentity> resolved,
		List<UnresolvedIdentity> unresolved
) {
	public IdentityResolutionResult {
		resolved = resolved == null ? List.of() : List.copyOf(resolved);
		unresolved = unresolved == null ? List.of() : List.copyOf(unresolved);
	}
}
