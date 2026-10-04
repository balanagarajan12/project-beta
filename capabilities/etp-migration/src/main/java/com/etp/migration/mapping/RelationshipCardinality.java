package com.etp.migration.mapping;

/**
 * Cardinalities the entity relationship model can describe.
 * MANY_TO_MANY is not a value of this enum. It needs association identity that a parent/child key pair does not capture.
 */
public enum RelationshipCardinality {
	ONE_TO_ONE,
	ONE_TO_MANY,
	MANY_TO_ONE
}
