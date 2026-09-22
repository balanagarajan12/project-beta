package com.etp.migration.batch;

import com.etp.migration.entity.MigrationBatchStatus;
import com.etp.migration.run.InvalidStateTransitionException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class MigrationBatchTransitions {

	private static final Map<MigrationBatchStatus, Set<MigrationBatchStatus>> ALLOWED = new EnumMap<>(MigrationBatchStatus.class);

	static {
		ALLOWED.put(MigrationBatchStatus.PENDING, EnumSet.of(MigrationBatchStatus.CLAIMED, MigrationBatchStatus.CANCELLED));
		ALLOWED.put(MigrationBatchStatus.RETRY_PENDING, EnumSet.of(MigrationBatchStatus.CLAIMED, MigrationBatchStatus.CANCELLED));
		ALLOWED.put(MigrationBatchStatus.CLAIMED, EnumSet.of(
				MigrationBatchStatus.PROCESSING,
				MigrationBatchStatus.PENDING,
				MigrationBatchStatus.CANCELLED));
		ALLOWED.put(MigrationBatchStatus.PROCESSING, EnumSet.of(
				MigrationBatchStatus.COMPLETED,
				MigrationBatchStatus.COMPLETED_WITH_ERRORS,
				MigrationBatchStatus.FAILED,
				MigrationBatchStatus.CLAIMED,
				MigrationBatchStatus.CANCELLED));
		ALLOWED.put(MigrationBatchStatus.FAILED, EnumSet.of(MigrationBatchStatus.RETRY_PENDING));
		ALLOWED.put(MigrationBatchStatus.COMPLETED_WITH_ERRORS, EnumSet.of(MigrationBatchStatus.RETRY_PENDING));
		ALLOWED.put(MigrationBatchStatus.COMPLETED, EnumSet.noneOf(MigrationBatchStatus.class));
		ALLOWED.put(MigrationBatchStatus.CANCELLED, EnumSet.noneOf(MigrationBatchStatus.class));
	}

	private MigrationBatchTransitions() {
	}

	public static boolean canTransition(MigrationBatchStatus from, MigrationBatchStatus to) {
		return ALLOWED.getOrDefault(from, Set.of()).contains(to);
	}

	public static void require(MigrationBatchStatus from, MigrationBatchStatus to) {
		if (!canTransition(from, to)) {
			throw new InvalidStateTransitionException(
					"Cannot transition migration batch from " + from + " to " + to);
		}
	}

	public static boolean isRetryable(MigrationBatchStatus status) {
		return status == MigrationBatchStatus.FAILED
				|| status == MigrationBatchStatus.COMPLETED_WITH_ERRORS;
	}

	public static boolean isInFlight(MigrationBatchStatus status) {
		return status == MigrationBatchStatus.PENDING
				|| status == MigrationBatchStatus.RETRY_PENDING
				|| status == MigrationBatchStatus.CLAIMED
				|| status == MigrationBatchStatus.PROCESSING;
	}
}
