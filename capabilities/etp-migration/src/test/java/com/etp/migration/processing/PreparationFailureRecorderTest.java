package com.etp.migration.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.etp.migration.entity.MigrationDefinition;
import com.etp.migration.entity.MigrationError;
import com.etp.migration.entity.MigrationRun;
import com.etp.migration.entity.MigrationRunStatus;
import com.etp.migration.repository.MigrationBatchRepository;
import com.etp.migration.repository.MigrationErrorRepository;
import com.etp.migration.repository.MigrationRunRepository;
import com.etp.migration.run.InvalidStateTransitionException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
class PreparationFailureRecorderTest {

	@Mock
	private MigrationRunRepository migrationRunRepository;
	@Mock
	private MigrationBatchRepository migrationBatchRepository;
	@Mock
	private MigrationErrorRepository migrationErrorRepository;
	@Mock
	private PlatformTransactionManager transactionManager;

	private PreparationFailureRecorder recorder;

	@BeforeEach
	void setUp() {
		recorder = new PreparationFailureRecorder(
				transactionManager,
				migrationRunRepository,
				migrationBatchRepository,
				migrationErrorRepository);
	}

	@Test
	void queuedUnpreparedRunIsFailedAndTheErrorOmitsUnknownSqlState() {
		UUID runId = UUID.randomUUID();
		MigrationRun run = queuedRun(runId, Map.of());
		when(migrationRunRepository.findById(runId)).thenReturn(Optional.of(run));
		when(migrationBatchRepository.countByMigrationRun_IdAndStatusIn(eq(runId), any())).thenReturn(0L);

		recorder.record(runId, new IllegalStateException("source file is missing"));

		assertThat(run.getStatus()).isEqualTo(MigrationRunStatus.FAILED);
		assertThat(run.getErrorMessage()).isEqualTo("source file is missing");
		assertThat(run.getStartedAt()).isNotNull();
		assertThat(run.getCompletedAt()).isNotNull();
		ArgumentCaptor<MigrationError> saved = ArgumentCaptor.forClass(MigrationError.class);
		verify(migrationErrorRepository).save(saved.capture());
		assertThat(saved.getValue().getErrorCode()).isEqualTo(MigrationErrorCodes.PREPARATION_FAILED);
		assertThat(saved.getValue().getErrorDetails())
				.containsEntry("phase", "PREPARE")
				.containsEntry("message", "source file is missing")
				.doesNotContainKey("sqlState")
				.doesNotContainKey("relation");
		assertThat(saved.getValue().isRetryable()).isFalse();
	}

	@Test
	void repeatedPreparationFailureDoesNotInsertASecondError() {
		UUID runId = UUID.randomUUID();
		MigrationRun run = queuedRun(runId, Map.of());
		when(migrationRunRepository.findById(runId)).thenReturn(Optional.of(run));
		when(migrationBatchRepository.countByMigrationRun_IdAndStatusIn(eq(runId), any())).thenReturn(0L);
		when(migrationErrorRepository
				.existsByMigrationRun_IdAndMigrationBatchIsNullAndStageAndErrorCodeAndErrorMessageAndSourceRecordKeyIsNull(
						eq(runId), any(), any(), any()))
				.thenReturn(true);

		recorder.record(runId, new IllegalStateException("source file is missing"));

		assertThat(run.getStatus()).isEqualTo(MigrationRunStatus.FAILED);
		assertThat(run.getErrorMessage()).isEqualTo("source file is missing");
		verify(migrationErrorRepository, never()).save(any());
	}

	@Test
	void runningUnpreparedRunIsFailed() {
		UUID runId = UUID.randomUUID();
		MigrationRun run = queuedRun(runId, Map.of());
		run.setStatus(MigrationRunStatus.RUNNING);
		when(migrationRunRepository.findById(runId)).thenReturn(Optional.of(run));
		when(migrationBatchRepository.countByMigrationRun_IdAndStatusIn(eq(runId), any())).thenReturn(0L);

		recorder.record(runId, new IllegalStateException("source count timed out"));

		assertThat(run.getStatus()).isEqualTo(MigrationRunStatus.FAILED);
		assertThat(run.getCompletedAt()).isNotNull();
		verify(migrationErrorRepository).save(any(MigrationError.class));
	}

	@Test
	void inFlightBatchesKeepTheRunQueued() {
		UUID runId = UUID.randomUUID();
		MigrationRun run = queuedRun(runId, Map.of());
		when(migrationRunRepository.findById(runId)).thenReturn(Optional.of(run));
		when(migrationBatchRepository.countByMigrationRun_IdAndStatusIn(eq(runId), any())).thenReturn(1L);

		recorder.record(runId, new IllegalStateException("planning failed"));

		assertThat(run.getStatus()).isEqualTo(MigrationRunStatus.QUEUED);
		assertThat(run.getErrorMessage()).isNull();
		verify(migrationErrorRepository).save(any(MigrationError.class));
	}

	@Test
	void alreadyPreparedRunStaysQueued() {
		UUID runId = UUID.randomUUID();
		MigrationRun run = queuedRun(runId, Map.of("statusMessage", SourceInspectionService.STATUS_SOURCE_INSPECTED));
		when(migrationRunRepository.findById(runId)).thenReturn(Optional.of(run));

		recorder.record(runId, new IllegalStateException("late failure"));

		assertThat(run.getStatus()).isEqualTo(MigrationRunStatus.QUEUED);
		verify(migrationErrorRepository, never()).save(any());
	}

	@Test
	void invalidStateTransitionDoesNotInspectTheRun() {
		recorder.record(UUID.randomUUID(), new InvalidStateTransitionException("Cannot prepare migration run in status RUNNING"));

		verify(migrationRunRepository, never()).findById(any());
		verify(migrationErrorRepository, never()).save(any());
	}

	private static MigrationRun queuedRun(UUID runId, Map<String, Object> snapshot) {
		MigrationDefinition definition = new MigrationDefinition();
		definition.setTargetEntity("address");
		MigrationRun run = new MigrationRun();
		run.setId(runId);
		run.setStatus(MigrationRunStatus.QUEUED);
		run.setMigrationDefinition(definition);
		run.setSourceSnapshot(new HashMap<>(snapshot));
		return run;
	}
}
