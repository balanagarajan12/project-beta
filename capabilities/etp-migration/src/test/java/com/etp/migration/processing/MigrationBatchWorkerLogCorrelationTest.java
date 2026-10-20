package com.etp.migration.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import com.etp.migration.batch.BatchClaim;
import com.etp.migration.batch.BatchClaimLostException;
import com.etp.migration.batch.MigrationBatchQueueService;
import com.etp.migration.config.WorkerIdentity;
import com.etp.migration.connector.source.MigrationSourceConnectorRegistry;
import com.etp.migration.connector.storage.ArtifactChecksumCalculator;
import com.etp.migration.connector.target.MigrationTargetConnectorRegistry;
import com.etp.migration.entity.MigrationBatch;
import com.etp.migration.entity.MigrationBatchStatus;
import com.etp.migration.entity.MigrationDefinition;
import com.etp.migration.entity.MigrationError;
import com.etp.migration.entity.MigrationMapping;
import com.etp.migration.entity.MigrationRun;
import com.etp.migration.execution.EntityFailureRecorder;
import com.etp.migration.execution.EntityRelationshipBatchProcessor;
import com.etp.migration.repository.MigrationBatchRepository;
import com.etp.migration.repository.MigrationErrorRepository;
import com.etp.migration.repository.MigrationMappingRuleRepository;
import com.etp.migration.repository.MigrationRunRepository;
import com.etp.migration.repository.MigrationSourceRepository;
import com.etp.migration.support.CapturedLogs;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

class MigrationBatchWorkerLogCorrelationTest {

	private static final UUID RUN_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
	private static final long BATCH_ID = 7L;
	private static final Map<String, String> CORRELATION = Map.of(
			MigrationLogContext.RUN_ID, RUN_ID.toString(),
			MigrationLogContext.BATCH_ID, String.valueOf(BATCH_ID));

	private final MigrationBatchQueueService queue = mock(MigrationBatchQueueService.class);
	private final MigrationBatchRepository batches = mock(MigrationBatchRepository.class);
	private final MigrationMappingRuleRepository rules = mock(MigrationMappingRuleRepository.class);
	private final MigrationErrorRepository errors = mock(MigrationErrorRepository.class);
	private MigrationBatchWorker worker;
	private MigrationBatch batch;

	@BeforeEach
	void setUp() {
		worker = new MigrationBatchWorker(
				mock(PlatformTransactionManager.class),
				queue,
				mock(WorkerIdentity.class),
				mock(MigrationRunRepository.class),
				batches,
				mock(MigrationSourceRepository.class),
				rules,
				errors,
				mock(MigrationSourceConnectorRegistry.class),
				mock(MigrationTargetConnectorRegistry.class),
				mock(ArtifactChecksumCalculator.class),
				mock(MigrationRecordProcessor.class),
				mock(EntityRelationshipBatchProcessor.class),
				mock(EntityFailureRecorder.class));
		MigrationMapping mapping = new MigrationMapping();
		mapping.setId(UUID.randomUUID());
		MigrationDefinition definition = new MigrationDefinition();
		definition.setId(UUID.randomUUID());
		definition.setMapping(mapping);
		MigrationRun run = new MigrationRun();
		run.setId(RUN_ID);
		run.setMigrationDefinition(definition);
		batch = new MigrationBatch();
		ReflectionTestUtils.setField(batch, "id", BATCH_ID);
		batch.setMigrationRun(run);
		batch.setBatchNumber(1);
		batch.setStatus(MigrationBatchStatus.CLAIMED);
		batch.setWorkerId("worker-a");
		batch.setAttemptCount(2);
		when(batches.findById(BATCH_ID)).thenReturn(Optional.of(batch));
		MDC.put("requestId", "outer");
	}

	@AfterEach
	void clearMdc() {
		MDC.clear();
	}

	@Test
	void lostClaimIsLoggedWithRunAndBatchAndTheMdcIsRestored() {
		doThrow(new BatchClaimLostException(BATCH_ID)).when(queue).beginProcessing(any(BatchClaim.class));

		try (CapturedLogs logs = CapturedLogs.of(MigrationBatchWorker.class)) {
			assertThatThrownBy(() -> worker.processBatch(BATCH_ID)).isInstanceOf(BatchClaimLostException.class);

			var lost = logs.containing("Batch claim lost");
			assertThat(lost).hasSize(1);
			assertThat(lost.get(0).level()).isEqualTo(Level.WARN);
			assertThat(lost.get(0).message()).contains("runId=" + RUN_ID, "batchId=" + BATCH_ID, "attempt=2");
			assertThat(lost.get(0).mdc()).containsAllEntriesOf(CORRELATION);
		}
		assertThat(MDC.get(MigrationLogContext.RUN_ID)).isNull();
		assertThat(MDC.get(MigrationLogContext.BATCH_ID)).isNull();
		assertThat(MDC.get("requestId")).isEqualTo("outer");
	}

	@Test
	void failedBatchLogsCodeAndTypeButNotTheExceptionMessage() {
		when(rules.findByMapping_IdAndEnabledTrueOrderBySequenceNoAsc(any()))
				.thenThrow(new IllegalStateException("password=do-not-log-me"));
		MigrationBatch failed = new MigrationBatch();
		ReflectionTestUtils.setField(failed, "id", BATCH_ID);
		failed.setMigrationRun(batch.getMigrationRun());
		failed.setBatchNumber(1);
		failed.setStatus(MigrationBatchStatus.FAILED);
		when(queue.failBatch(any(BatchClaim.class), anyString())).thenReturn(failed);

		try (CapturedLogs logs = CapturedLogs.of(MigrationBatchWorker.class)) {
			ProcessBatchResponse response = worker.processBatch(BATCH_ID);

			assertThat(response.status()).isEqualTo("FAILED");
			assertThat(logs.entries()).extracting(CapturedLogs.Entry::message)
					.anySatisfy(message -> assertThat(message).startsWith("Batch processing started"))
					.anySatisfy(message -> assertThat(message).startsWith("Batch processing failed"))
					.anySatisfy(message -> assertThat(message).startsWith("Batch processing finished"))
					.noneSatisfy(message -> assertThat(message).contains("do-not-log-me"));
			assertThat(logs.entries()).allSatisfy(entry -> {
				assertThat(entry.mdc()).containsAllEntriesOf(CORRELATION);
				assertThat(entry.hasThrowable()).isFalse();
			});
			var failure = logs.containing("Batch processing failed").get(0);
			assertThat(failure.level()).isEqualTo(Level.WARN);
			assertThat(failure.message()).contains(
					"errorCode=" + MigrationErrorCodes.SOURCE_READ_ERROR,
					"exceptionType=java.lang.IllegalStateException",
					"cause=IllegalStateException");
		}
		ArgumentCaptor<MigrationError> saved = ArgumentCaptor.forClass(MigrationError.class);
		verify(errors).save(saved.capture());
		assertThat(saved.getValue().getErrorDetails())
				.containsEntry("errorCode", MigrationErrorCodes.SOURCE_READ_ERROR)
				.containsEntry("phase", "EXTRACT")
				.containsEntry("exceptionType", IllegalStateException.class.getName())
				.containsEntry("runId", RUN_ID.toString())
				.containsEntry("batchId", BATCH_ID)
				.doesNotContainKey("sqlState")
				.doesNotContainKey("relation")
				.doesNotContainKey("message")
				.doesNotContainKey("rootCauseMessage");
		assertThat(saved.getValue().getErrorDetails().toString()).doesNotContain("do-not-log-me");
		assertThat(MDC.get(MigrationLogContext.RUN_ID)).isNull();
		assertThat(MDC.get(MigrationLogContext.BATCH_ID)).isNull();
		assertThat(MDC.get("requestId")).isEqualTo("outer");
	}
}
