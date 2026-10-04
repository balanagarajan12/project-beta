package com.etp.migration.identity;

/**
 * A direct parent source identity and, when known, its target identity.
 * This is not a copy of the parent work item.
 */
public record ParentIdentityReference(
		EntityIdentity source,
		String targetIdentity,
		ParentIdentityStatus status
) {
}
