package com.etp.migration.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.etp.migration.entity.MigrationBatchStatus;
import com.etp.migration.entity.MigrationRun;
import com.etp.migration.entity.MigrationRunStatus;
import com.etp.migration.repository.MigrationBatchRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RunCompletionEvaluatorTest {

	@Mock
	private MigrationBatchRepository migrationBatchRepository;

	@Test
	void unresolvedRelationshipRejectionsCompleteTheRunWithErrors() {
		MigrationRun run = runningRun();
		run.setRejectedRecords(2L);
		when(migrationBatchRepository.countByMigrationRun_IdAndStatusIn(eq(run.getId()), any())).thenReturn(0L);

		new RunCompletionEvaluator(migrationBatchRepository).evaluate(run, Instant.parse("2026-10-20T09:43:55Z"));

		assertThat(run.getStatus()).isEqualTo(MigrationRunStatus.COMPLETED_WITH_ERRORS);
		assertThat(run.getErrorMessage()).isEqualTo("One or more records were not loaded");
	}

	@Test
	void aFailedBatchFailsTheRunAndKeepsASummary() {
		MigrationRun run = runningRun();
		when(migrationBatchRepository.countByMigrationRun_IdAndStatusIn(eq(run.getId()), any()))
				.thenAnswer(invocation -> {
					java.util.Collection<?> statuses = invocation.getArgument(1);
					return statuses.contains(MigrationBatchStatus.FAILED) ? 1L : 0L;
				});

		new RunCompletionEvaluator(migrationBatchRepository).evaluate(run, Instant.parse("2026-10-20T09:43:55Z"));

		assertThat(run.getStatus()).isEqualTo(MigrationRunStatus.FAILED);
		assertThat(run.getErrorMessage()).isEqualTo("One or more execution batches failed");
	}

	@Test
	void aRunWithNoRejectionsCompletes() {
		MigrationRun run = runningRun();
		run.setRejectedRecords(0L);
		when(migrationBatchRepository.countByMigrationRun_IdAndStatusIn(eq(run.getId()), any())).thenReturn(0L);

		new RunCompletionEvaluator(migrationBatchRepository).evaluate(run, Instant.parse("2026-10-20T09:43:55Z"));

		assertThat(run.getStatus()).isEqualTo(MigrationRunStatus.COMPLETED);
		assertThat(run.getErrorMessage()).isNull();
	}

	private static MigrationRun runningRun() {
		MigrationRun run = new MigrationRun();
		run.setId(UUID.fromString("fcb3bfde-2e5c-4a55-a9f8-689de09eb0cb"));
		run.setStatus(MigrationRunStatus.RUNNING);
		return run;
	}
}
