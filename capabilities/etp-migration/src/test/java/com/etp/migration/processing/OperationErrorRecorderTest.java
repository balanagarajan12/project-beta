package com.etp.migration.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.etp.migration.entity.MigrationBatch;
import com.etp.migration.entity.MigrationError;
import com.etp.migration.entity.MigrationPromotion;
import com.etp.migration.entity.MigrationPromotionBatch;
import com.etp.migration.entity.MigrationRun;
import com.etp.migration.entity.MigrationRunStatus;
import com.etp.migration.entity.MigrationStage;
import com.etp.migration.entity.PromotionBatchStatus;
import com.etp.migration.promotion.PromotionException;
import com.etp.migration.repository.MigrationErrorRepository;
import com.etp.migration.repository.MigrationRunRepository;
import com.etp.migration.run.MigrationPhase;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class OperationErrorRecorderTest {

	@Mock
	private MigrationErrorRepository errors;

	@Mock
	private MigrationRunRepository runs;

	private OperationErrorRecorder recorder;

	@BeforeEach
	void setUp() {
		recorder = new OperationErrorRecorder(errors, runs);
	}

	@Test
	void nullPointerPromotionFailureStoresTheFrameWithoutTheStack() {
		UUID runId = UUID.randomUUID();
		UUID promotionId = UUID.randomUUID();
		MigrationPromotionBatch batch = promotionBatch(runId, promotionId, 15L, 1501L);
		NullPointerException failure = new NullPointerException();
		failure.setStackTrace(new StackTraceElement[] {
				new StackTraceElement(
						"com.etp.migration.connector.target.uam.UamCanonicalRows",
						"referencedValue",
						"UamCanonicalRows.java",
						146)
		});
		summarize(batch, failure);

		recorder.recordPromotionBatch(batch, failure);

		ArgumentCaptor<MigrationError> saved = ArgumentCaptor.forClass(MigrationError.class);
		verify(errors).save(saved.capture());
		MigrationError error = saved.getValue();
		assertThat(error.getStage()).isEqualTo(MigrationStage.PROMOTE);
		assertThat(error.getErrorCode()).isEqualTo(MigrationErrorCodes.PROMOTION_BATCH_FAILED);
		assertThat(error.getErrorType()).isEqualTo("PROMOTION_BATCH");
		assertThat(error.getErrorMessage()).isEqualTo(batch.getErrorMessage())
				.contains("UamCanonicalRows.referencedValue(UamCanonicalRows.java:146)")
				.doesNotContain("\n");
		assertThat(error.getMigrationBatch().getId()).isEqualTo(1501L);
		assertThat(error.isRetryable()).isTrue();
		assertThat(error.getErrorDetails())
				.containsEntry("category", "BATCH")
				.containsEntry("promotionId", promotionId.toString())
				.containsEntry("promotionBatchId", 15L)
				.containsEntry("location", batch.getErrorMessage())
				.doesNotContainKeys("stackTrace", "stack");
		verify(errors, never()).delete(any());
	}

	@Test
	void secretPromotionMessageIsNotStored() {
		UUID runId = UUID.randomUUID();
		MigrationPromotionBatch batch = promotionBatch(runId, UUID.randomUUID(), 4L, 8L);
		IllegalStateException failure = new IllegalStateException("password=hunter2");
		summarize(batch, failure);

		recorder.recordPromotionBatch(batch, failure);

		ArgumentCaptor<MigrationError> saved = ArgumentCaptor.forClass(MigrationError.class);
		verify(errors).save(saved.capture());
		assertThat(batch.getErrorMessage()).isEqualTo(IllegalStateException.class.getName());
		assertThat(saved.getValue().getErrorMessage()).isEqualTo(IllegalStateException.class.getName());
		assertThat(saved.getValue().getErrorDetails().toString()).doesNotContain("hunter2");
	}

	@Test
	void aDifferentMessageIsStoredAgain() {
		UUID runId = UUID.randomUUID();
		MigrationPromotionBatch batch = promotionBatch(runId, UUID.randomUUID(), 4L, 8L);
		IllegalStateException failure = new IllegalStateException("parent was not resolved");
		summarize(batch, failure);
		when(errors.existsByMigrationRun_IdAndMigrationBatch_IdAndStageAndErrorCodeAndErrorMessageAndSourceRecordKeyIsNull(
				runId, 8L, MigrationStage.PROMOTE, batch.getErrorCode(), batch.getErrorMessage()))
				.thenReturn(false);

		recorder.recordPromotionBatch(batch, failure);

		verify(errors).save(any(MigrationError.class));
		verify(errors, never()).delete(any());
	}

	@Test
	void identicalPromotionFailureIsNotInsertedAgain() {
		UUID runId = UUID.randomUUID();
		MigrationPromotionBatch batch = promotionBatch(runId, UUID.randomUUID(), 4L, 8L);
		IllegalStateException failure = new IllegalStateException("parent was not resolved");
		summarize(batch, failure);
		when(errors.existsByMigrationRun_IdAndMigrationBatch_IdAndStageAndErrorCodeAndErrorMessageAndSourceRecordKeyIsNull(
				runId, 8L, MigrationStage.PROMOTE, batch.getErrorCode(), batch.getErrorMessage()))
				.thenReturn(true);

		recorder.recordPromotionBatch(batch, failure);

		verify(errors, never()).save(any());
		verify(errors, never()).delete(any());
	}

	@Test
	void phaseFailureIsStoredOnTheRunAndDoesNotCompleteThePhase() {
		UUID runId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		MigrationRun run = new MigrationRun();
		run.setId(runId);
		when(runs.findById(runId)).thenReturn(Optional.of(run));
		NullPointerException failure = new NullPointerException();
		failure.setStackTrace(new StackTraceElement[] {
				new StackTraceElement(
						"com.etp.migration.promotion.PromotionExecutionService",
						"promote",
						"PromotionExecutionService.java",
						119)
		});
		String message = MigrationErrorDetails.diagnostic(failure);

		recorder.recordPhase(
				runId,
				jobId,
				MigrationPhase.PROMOTION,
				MigrationErrorCodes.PREPARATION_FAILED,
				message,
				failure,
				true);

		ArgumentCaptor<MigrationError> saved = ArgumentCaptor.forClass(MigrationError.class);
		verify(errors).save(saved.capture());
		MigrationError error = saved.getValue();
		assertThat(error.getMigrationRun()).isSameAs(run);
		assertThat(error.getMigrationBatch()).isNull();
		assertThat(error.getStage()).isEqualTo(MigrationStage.PROMOTE);
		assertThat(error.getErrorType()).isEqualTo("PHASE_FAILED");
		assertThat(error.getErrorMessage()).contains("PromotionExecutionService.promote");
		assertThat(error.getErrorDetails())
				.containsEntry("category", "PHASE")
				.containsEntry("phase", "PROMOTION")
				.containsEntry("phaseJobId", jobId.toString())
				.doesNotContainKey("stackTrace");
		assertThat(run.getStatus()).isEqualTo(MigrationRunStatus.CREATED);
	}

	@Test
	void configurationPhaseFailureIsNotRetryable() {
		UUID runId = UUID.randomUUID();
		MigrationRun run = new MigrationRun();
		run.setId(runId);
		when(runs.findById(runId)).thenReturn(Optional.of(run));
		PromotionException failure = new PromotionException(
				HttpStatus.CONFLICT.value(),
				MigrationErrorCodes.PROMOTION_NOT_READY,
				"not ready");

		recorder.recordPhase(
				runId, UUID.randomUUID(), MigrationPhase.PROMOTION_PREPARATION,
				failure.errorCode(), failure.getMessage(), failure, false);

		ArgumentCaptor<MigrationError> saved = ArgumentCaptor.forClass(MigrationError.class);
		verify(errors).save(saved.capture());
		assertThat(saved.getValue().getStage()).isEqualTo(MigrationStage.PROMOTE);
		assertThat(saved.getValue().isRetryable()).isFalse();
		assertThat(saved.getValue().getErrorDetails()).containsEntry("category", "CONFIGURATION");
	}

	@Test
	void repeatedPhaseFailureDoesNotInsertASecondError() {
		UUID runId = UUID.randomUUID();
		when(errors.existsByMigrationRun_IdAndMigrationBatchIsNullAndStageAndErrorCodeAndErrorMessageAndSourceRecordKeyIsNull(
				runId, MigrationStage.RECONCILE, MigrationErrorCodes.PREPARATION_FAILED, "database timeout"))
				.thenReturn(true);

		recorder.recordPhase(
				runId,
				UUID.randomUUID(),
				MigrationPhase.RECONCILIATION,
				MigrationErrorCodes.PREPARATION_FAILED,
				"database timeout",
				new IllegalStateException("database timeout"),
				true);

		verify(runs, never()).findById(any());
		verify(errors, never()).save(any());
	}

	@Test
	void detailedErrorIsWrittenAfterTheFailedProcessingTransactionRollsBack() {
		UUID runId = UUID.randomUUID();
		MigrationPromotionBatch batch = promotionBatch(runId, UUID.randomUUID(), 9L, 3L);
		NullPointerException failure = new NullPointerException();
		java.util.List<String> events = new java.util.ArrayList<>();
		PlatformTransactionManager transactions = org.mockito.Mockito.mock(PlatformTransactionManager.class);
		when(transactions.getTransaction(any())).thenAnswer(invocation -> {
			events.add("begin");
			return org.mockito.Mockito.mock(TransactionStatus.class);
		});
		org.mockito.Mockito.doAnswer(invocation -> {
			events.add("commit");
			return null;
		}).when(transactions).commit(any());
		org.mockito.Mockito.doAnswer(invocation -> {
			events.add("rollback");
			return null;
		}).when(transactions).rollback(any());
		TransactionTemplate template = new TransactionTemplate(transactions);

		try {
			template.executeWithoutResult(status -> {
				throw failure;
			});
		} catch (NullPointerException ex) {
			summarize(batch, ex);
			template.executeWithoutResult(status -> recorder.recordPromotionBatch(batch, ex));
		}

		assertThat(events).containsExactly("begin", "rollback", "begin", "commit");
		assertThat(batch.getStatus()).isEqualTo(PromotionBatchStatus.FAILED);
		verify(errors).save(any(MigrationError.class));
	}

	private static void summarize(MigrationPromotionBatch batch, RuntimeException failure) {
		batch.setStatus(PromotionBatchStatus.FAILED);
		batch.setErrorCode(MigrationErrorCodes.PROMOTION_BATCH_FAILED);
		batch.setErrorMessage(MigrationErrorDetails.summary(failure));
		batch.setFailedRecordCount(1L);
	}

	private static MigrationPromotionBatch promotionBatch(
			UUID runId, UUID promotionId, long promotionBatchId, long migrationBatchId) {
		MigrationRun run = new MigrationRun();
		run.setId(runId);
		MigrationBatch migrationBatch = new MigrationBatch();
		ReflectionTestUtils.setField(migrationBatch, "id", migrationBatchId);
		migrationBatch.setMigrationRun(run);
		MigrationPromotion promotion = new MigrationPromotion();
		promotion.setId(promotionId);
		promotion.setMigrationRun(run);
		MigrationPromotionBatch batch = new MigrationPromotionBatch();
		ReflectionTestUtils.setField(batch, "id", promotionBatchId);
		batch.setPromotion(promotion);
		batch.setMigrationRun(run);
		batch.setMigrationBatch(migrationBatch);
		batch.setStatus(PromotionBatchStatus.PROMOTING);
		return batch;
	}
}
