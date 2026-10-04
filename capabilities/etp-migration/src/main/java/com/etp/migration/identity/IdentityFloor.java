package com.etp.migration.identity;

/**
 * Highest number already present for a namespace. Used only when that namespace is first initialized.
 */
@FunctionalInterface
public interface IdentityFloor {

	long existingMaximum();
}
