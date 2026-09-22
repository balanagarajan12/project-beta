package com.etp.migration.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.etp.migration.entity.MigrationRunStatus;
import org.junit.jupiter.api.Test;

class MigrationRunTransitionsTest {

	@Test
	void allowsQueuedToRunning() {
		assertThat(MigrationRunTransitions.canTransition(MigrationRunStatus.QUEUED, MigrationRunStatus.RUNNING)).isTrue();
	}

	@Test
	void rejectsTerminalToQueued() {
		assertThatThrownBy(() -> MigrationRunTransitions.require(
				MigrationRunStatus.COMPLETED, MigrationRunStatus.QUEUED))
				.isInstanceOf(InvalidStateTransitionException.class);
	}
}
