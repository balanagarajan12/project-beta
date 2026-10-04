package com.etp.migration.identity;

/**
 * Reserves a contiguous range of positive integers for one namespace.
 * The returned value is the first number in the range. The last number is {@code start + count - 1}.
 * A namespace is the configured id prefix. Callers format the numbers separately.
 */
public interface TargetIdentityAllocator {

	long reserve(String namespace, int count, IdentityFloor floor);
}
