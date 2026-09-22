package com.etp.migration.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.etp.migration.entity.MigrationBatchStatus;
import com.etp.migration.run.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;

class MigrationBatchTransitionsTest {

	@Test
	void allowsPendingToClaimed() {
		assertThat(MigrationBatchTransitions.canTransition(
				MigrationBatchStatus.PENDING, MigrationBatchStatus.CLAIMED)).isTrue();
	}

	@Test
	void rejectsCompletedToClaimed() {
		assertThatThrownBy(() -> MigrationBatchTransitions.require(
				MigrationBatchStatus.COMPLETED, MigrationBatchStatus.CLAIMED))
				.isInstanceOf(InvalidStateTransitionException.class);
	}
}
