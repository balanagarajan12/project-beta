package com.etp.migration.identity;

/**
 * Whether a parent source identity has a known target identity.
 * {@link #UNRESOLVED} does not invent a target id and does not reuse the source id.
 */
public enum ParentIdentityStatus {
	RESOLVED,
	UNRESOLVED
}
