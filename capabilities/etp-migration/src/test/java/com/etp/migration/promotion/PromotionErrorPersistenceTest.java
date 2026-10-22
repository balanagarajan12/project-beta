package com.etp.migration.promotion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.etp.migration.connector.target.postgresql.PostgreSqlPromotionWriter;
import com.etp.migration.connector.target.uam.UamStagingPromotion;
import com.etp.migration.entity.MigrationPromotion;
import com.etp.migration.entity.MigrationPromotionBatch;
import com.etp.migration.entity.PromotionBatchStatus;
import com.etp.migration.entity.PromotionStatus;
import com.etp.migration.processing.MigrationErrorCodes;
import com.etp.migration.processing.OperationErrorRecorder;
import com.etp.migration.repository.MigrationBatchRepository;
import com.etp.migration.repository.MigrationPromotionBatchRepository;
import com.etp.migration.repository.MigrationPromotionRepository;
import com.etp.migration.repository.MigrationReconciliationRepository;
import com.etp.migration.repository.MigrationRunRepository;
import com.etp.migration.run.ExecutionControlService;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
class PromotionErrorPersistenceTest {

	@Mock
	private PlatformTransactionManager transactions;

	@Mock
	private MigrationRunRepository runs;

	@Mock
	private MigrationBatchRepository batches;

	@Mock
	private MigrationReconciliationRepository reconciliations;

	@Mock
	private MigrationPromotionRepository promotions;

	@Mock
	private MigrationPromotionBatchRepository promotionBatches;

	@Mock
	private PostgreSqlPromotionWriter writer;

	@Mock
	private UamStagingPromotion uam;

	@Mock
	private ExecutionControlService controls;

	@Mock
	private OperationErrorRecorder recorder;

	private PromotionExecutionService service;

	@BeforeEach
	void setUp() {
		service = new PromotionExecutionService(
				transactions,
				runs,
				batches,
				reconciliations,
				promotions,
				promotionBatches,
				writer,
				uam,
				controls,
				recorder);
	}

	@Test
	void failedBatchKeepsTheSummaryAndWritesTheDetailedError() {
		MigrationPromotionBatch batch = new MigrationPromotionBatch();
		ReflectionTestUtils.setField(batch, "id", 15L);
		NullPointerException failure = new NullPointerException();
		failure.setStackTrace(new StackTraceElement[] {
				new StackTraceElement(
						"com.etp.migration.connector.target.uam.UamCanonicalRows",
						"referencedValue",
						"UamCanonicalRows.java",
						146)
		});

		service.recordBatchFailure(batch, 1000L, failure);

		assertThat(batch.getStatus()).isEqualTo(PromotionBatchStatus.FAILED);
		assertThat(batch.getErrorCode()).isEqualTo(MigrationErrorCodes.PROMOTION_BATCH_FAILED);
		assertThat(batch.getPromotedRecordCount()).isZero();
		assertThat(batch.getFailedRecordCount()).isEqualTo(1000L);
		assertThat(batch.getErrorMessage()).contains("UamCanonicalRows.referencedValue");
		verify(recorder).recordPromotionBatch(batch, failure);
	}

	@Test
	void partialPromotionKeepsPartialStatusAndAPromotionSummary() {
		MigrationPromotion promotion = new MigrationPromotion();
		promotion.setStatus(PromotionStatus.PARTIALLY_PROMOTED);
		promotion.setErrorCode("stale");
		promotion.setErrorMessage("stale");

		PromotionExecutionService.assignTerminalError(promotion, PromotionStatus.PARTIALLY_PROMOTED, 2);

		assertThat(promotion.getStatus()).isEqualTo(PromotionStatus.PARTIALLY_PROMOTED);
		assertThat(promotion.getErrorCode()).isEqualTo(MigrationErrorCodes.PROMOTION_PARTIAL_FAILURE);
		assertThat(promotion.getErrorMessage()).isEqualTo("Promotion completed with one or more failed batches");
	}

	@Test
	void totalFailureStaysDistinctFromPartialFailure() {
		MigrationPromotion promotion = new MigrationPromotion();

		PromotionExecutionService.assignTerminalError(promotion, PromotionStatus.PROMOTION_FAILED, 4);

		assertThat(promotion.getErrorCode()).isEqualTo(MigrationErrorCodes.PROMOTION_BATCH_FAILED);
		assertThat(promotion.getErrorCode()).isNotEqualTo(MigrationErrorCodes.PROMOTION_PARTIAL_FAILURE);
	}

	@Test
	void successfulPromotionClearsAStaleSummary() {
		MigrationPromotion promotion = new MigrationPromotion();
		promotion.setErrorCode(MigrationErrorCodes.PROMOTION_PARTIAL_FAILURE);
		promotion.setErrorMessage("Promotion completed with one or more failed batches");

		PromotionExecutionService.assignTerminalError(promotion, PromotionStatus.PROMOTED, 0);

		assertThat(promotion.getErrorCode()).isNull();
		assertThat(promotion.getErrorMessage()).isNull();
	}

	@Test
	void promotedBatchDoesNotWriteAnError() throws Exception {
		MigrationPromotionBatch batch = new MigrationPromotionBatch();
		batch.setStatus(PromotionBatchStatus.PROMOTING);
		batch.setErrorCode("previous");
		batch.setErrorMessage("previous");
		when(promotionBatches.findById(9L)).thenReturn(Optional.of(batch));
		Class<?> eligibleType = null;
		for (Class<?> nested : PromotionExecutionService.class.getDeclaredClasses()) {
			if ("EligibleBatch".equals(nested.getSimpleName())) {
				eligibleType = nested;
			}
		}
		Constructor<?> constructor = eligibleType.getDeclaredConstructor(Long.class, Long.class, long.class);
		constructor.setAccessible(true);
		Object eligible = constructor.newInstance(9L, 3L, 4L);
		Method markPromoted = PromotionExecutionService.class.getDeclaredMethod("markBatchPromoted", eligibleType);
		markPromoted.setAccessible(true);

		markPromoted.invoke(service, eligible);

		assertThat(batch.getStatus()).isEqualTo(PromotionBatchStatus.PROMOTED);
		assertThat(batch.getErrorCode()).isNull();
		assertThat(batch.getErrorMessage()).isNull();
		assertThat(batch.getFailedRecordCount()).isZero();
		verify(recorder, never()).recordPromotionBatch(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
	}
}
