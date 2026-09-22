package com.etp.migration.run;

import com.etp.migration.entity.MigrationRunStatus;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class MigrationRunTransitions {

	private static final Map<MigrationRunStatus, Set<MigrationRunStatus>> ALLOWED = new EnumMap<>(MigrationRunStatus.class);

	static {
		ALLOWED.put(MigrationRunStatus.CREATED, EnumSet.of(
				MigrationRunStatus.QUEUED,
				MigrationRunStatus.CANCEL_REQUESTED));
		ALLOWED.put(MigrationRunStatus.QUEUED, EnumSet.of(
				MigrationRunStatus.RUNNING,
				MigrationRunStatus.CANCEL_REQUESTED));
		ALLOWED.put(MigrationRunStatus.RUNNING, EnumSet.of(
				MigrationRunStatus.COMPLETED,
				MigrationRunStatus.COMPLETED_WITH_ERRORS,
				MigrationRunStatus.FAILED,
				MigrationRunStatus.CANCEL_REQUESTED));
		ALLOWED.put(MigrationRunStatus.CANCEL_REQUESTED, EnumSet.of(MigrationRunStatus.CANCELLED));
		ALLOWED.put(MigrationRunStatus.FAILED, EnumSet.of(MigrationRunStatus.QUEUED));
		ALLOWED.put(MigrationRunStatus.COMPLETED_WITH_ERRORS, EnumSet.of(MigrationRunStatus.QUEUED));
		ALLOWED.put(MigrationRunStatus.PAUSED, EnumSet.noneOf(MigrationRunStatus.class));
		ALLOWED.put(MigrationRunStatus.COMPLETED, EnumSet.noneOf(MigrationRunStatus.class));
		ALLOWED.put(MigrationRunStatus.CANCELLED, EnumSet.noneOf(MigrationRunStatus.class));
	}

	private MigrationRunTransitions() {
	}

	public static boolean canTransition(MigrationRunStatus from, MigrationRunStatus to) {
		return ALLOWED.getOrDefault(from, Set.of()).contains(to);
	}

	public static void require(MigrationRunStatus from, MigrationRunStatus to) {
		if (!canTransition(from, to)) {
			throw new InvalidStateTransitionException(
					"Cannot transition migration run from " + from + " to " + to);
		}
	}

	public static boolean isTerminal(MigrationRunStatus status) {
		return status == MigrationRunStatus.COMPLETED
				|| status == MigrationRunStatus.COMPLETED_WITH_ERRORS
				|| status == MigrationRunStatus.FAILED
				|| status == MigrationRunStatus.CANCELLED;
	}
}
