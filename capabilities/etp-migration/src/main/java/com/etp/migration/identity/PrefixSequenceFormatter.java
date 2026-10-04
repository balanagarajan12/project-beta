package com.etp.migration.identity;

/**
 * Formats a reserved number. It does not decide which number is next.
 */
public final class PrefixSequenceFormatter {

	private PrefixSequenceFormatter() {
	}

	public static String format(String prefix, int sequenceLength, long sequence) {
		if (prefix == null || !prefix.matches("[A-Za-z][A-Za-z0-9-]*")) {
			throw new IllegalArgumentException("Identity prefix is invalid");
		}
		if (sequenceLength < 1 || sequenceLength > 18) {
			throw new IllegalArgumentException("Identity sequence length is invalid");
		}
		if (sequence < 1) {
			throw new IllegalArgumentException("Identity sequence is invalid");
		}
		String digits = Long.toString(sequence);
		if (digits.length() > sequenceLength) {
			throw new IllegalArgumentException("Identity sequence exceeds the configured length");
		}
		return prefix + "0".repeat(sequenceLength - digits.length()) + digits;
	}

	public static String prefixSourceValue(String prefix, String sourceIdentity) {
		if (prefix == null || !prefix.matches("[A-Za-z][A-Za-z0-9-]*")) {
			throw new IllegalArgumentException("Identity prefix is invalid");
		}
		if (sourceIdentity == null || sourceIdentity.isBlank()) {
			throw new IllegalArgumentException("Source identity is required");
		}
		return prefix + sourceIdentity.trim();
	}
}
