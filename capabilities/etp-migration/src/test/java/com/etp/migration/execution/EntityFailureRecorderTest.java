package com.etp.migration.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.etp.migration.entity.MigrationBatch;
import com.etp.migration.entity.MigrationError;
import com.etp.migration.entity.MigrationRun;
import com.etp.migration.entity.MigrationStage;
import com.etp.migration.processing.MigrationErrorCodes;
import com.etp.migration.repository.MigrationBatchRepository;
import com.etp.migration.repository.MigrationErrorRepository;
import com.etp.migration.repository.MigrationRunRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
class EntityFailureRecorderTest {

	@Mock
	private MigrationRunRepository runs;

	@Mock
	private MigrationBatchRepository batches;

	@Mock
	private MigrationErrorRepository errors;

	@Mock
	private PlatformTransactionManager transactions;

	private EntityFailureRecorder recorder;

	@BeforeEach
	void setUp() {
		recorder = new EntityFailureRecorder(transactions, runs, batches, errors);
	}

	@Test
	void validationErrorIsStoredOnTheRecord() {
		UUID runId = UUID.randomUUID();
		MigrationRun run = new MigrationRun();
		run.setId(runId);
		MigrationBatch batch = new MigrationBatch();
		ReflectionTestUtils.setField(batch, "id", 7L);
		when(runs.findById(runId)).thenReturn(Optional.of(run));
		when(batches.findById(7L)).thenReturn(Optional.of(batch));

		recorder.save(runId, 7L, List.of(new EntityItemFailure(
				41L,
				"GeographicAddress",
				"addr-41",
				MigrationStage.VALIDATE,
				MigrationErrorCodes.VALIDATION_ERROR,
				"VALIDATION",
				"city",
				"city is required",
				java.util.Map.of("targetEntity", "address"),
				false)));

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<MigrationError>> saved = ArgumentCaptor.forClass(List.class);
		verify(errors).saveAll(saved.capture());
		MigrationError error = saved.getValue().get(0);
		assertThat(error.getStage()).isEqualTo(MigrationStage.VALIDATE);
		assertThat(error.getErrorCode()).isEqualTo(MigrationErrorCodes.VALIDATION_ERROR);
		assertThat(error.getErrorMessage()).isEqualTo("city is required");
		assertThat(error.getSourceRecordKey()).isEqualTo("addr-41");
		assertThat(error.getMigrationRun().getId()).isEqualTo(runId);
		assertThat(error.getFieldName()).isEqualTo("city");
		assertThat(error.getMigrationBatch()).isSameAs(batch);
		assertThat(error.getErrorDetails()).containsEntry("category", "RECORD");
		assertThat(error.isRetryable()).isFalse();
	}

	@Test
	void sensitiveRecordMessageIsReplaced() {
		UUID runId = UUID.randomUUID();
		MigrationRun run = new MigrationRun();
		run.setId(runId);
		when(runs.findById(runId)).thenReturn(Optional.of(run));
		when(batches.findById(7L)).thenReturn(Optional.of(new MigrationBatch()));

		recorder.save(runId, 7L, List.of(new EntityItemFailure(
				1L, "GeographicAddress", null, MigrationStage.VALIDATE,
				MigrationErrorCodes.VALIDATION_ERROR, "VALIDATION", null,
				"password=hunter2", java.util.Map.of(), false)));

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<MigrationError>> saved = ArgumentCaptor.forClass(List.class);
		verify(errors).saveAll(saved.capture());
		assertThat(saved.getValue().get(0).getErrorMessage()).isEqualTo("Record processing failed");
		assertThat(saved.getValue().get(0).getErrorMessage()).doesNotContain("hunter2");
	}

	@Test
	void identicalRecordFailureIsNotInsertedAgain() {
		UUID runId = UUID.randomUUID();
		MigrationRun run = new MigrationRun();
		run.setId(runId);
		when(runs.findById(runId)).thenReturn(Optional.of(run));
		when(batches.findById(7L)).thenReturn(Optional.of(new MigrationBatch()));
		when(errors.existsByMigrationRun_IdAndMigrationBatch_IdAndSourceRecordKeyAndStageAndErrorCodeAndErrorMessage(
				runId, 7L, "addr-1", MigrationStage.VALIDATE, MigrationErrorCodes.VALIDATION_ERROR, "city is required"))
				.thenReturn(true);

		recorder.save(runId, 7L, List.of(new EntityItemFailure(
				1L, "GeographicAddress", "addr-1", MigrationStage.VALIDATE,
				MigrationErrorCodes.VALIDATION_ERROR, "VALIDATION", "city",
				"city is required", java.util.Map.of(), false)));

		verify(errors).saveAll(List.of());
	}

	@Test
	void successfulItemsDoNotCreateErrors() {
		recorder.save(UUID.randomUUID(), 7L, List.of());

		verify(errors, never()).saveAll(any());
		verify(runs, never()).findById(any());
	}
}
