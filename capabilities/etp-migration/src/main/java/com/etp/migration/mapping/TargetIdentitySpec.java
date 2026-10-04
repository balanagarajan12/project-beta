package com.etp.migration.mapping;

/**
 * How a generated target id should look. This does not choose the next number.
 * {@code sequenceLength} is used by {@code PREFIX_SEQUENCE} only.
 */
public record TargetIdentitySpec(String strategy, String prefix, Integer sequenceLength) {

	public static final String PREFIX_SEQUENCE = "PREFIX_SEQUENCE";
	public static final String PREFIX_SOURCE_VALUE = "PREFIX_SOURCE_VALUE";
}
