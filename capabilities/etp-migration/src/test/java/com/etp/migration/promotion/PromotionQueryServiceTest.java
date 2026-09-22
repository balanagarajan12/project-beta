package com.etp.migration.promotion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.etp.migration.entity.MigrationBatch;
import com.etp.migration.entity.MigrationPromotion;
import com.etp.migration.entity.MigrationPromotionBatch;
import com.etp.migration.entity.MigrationRun;
import com.etp.migration.entity.PromotionBatchStatus;
import com.etp.migration.entity.PromotionStatus;
import com.etp.migration.promotion.dto.ExecutePromotionResponse;
import com.etp.migration.repository.MigrationPromotionBatchRepository;
import com.etp.migration.repository.MigrationPromotionRepository;
import com.etp.migration.repository.MigrationRunRepository;
import com.etp.migration.run.MigrationRunNotFoundException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PromotionQueryServiceTest {

	@Mock
	private MigrationRunRepository migrationRunRepository;
	@Mock
	private MigrationPromotionRepository promotionRepository;
	@Mock
	private MigrationPromotionBatchRepository promotionBatchRepository;

	private PromotionQueryService service;
	private UUID runId;
	private UUID promotionId;

	@BeforeEach
	void setUp() {
		service = new PromotionQueryService(migrationRunRepository, promotionRepository, promotionBatchRepository);
		runId = UUID.fromString("87492c12-d598-434d-b0ea-58da2e2e0b44");
		promotionId = UUID.fromString("89b012a6-a857-41b0-be96-168e4de139da");
	}

	@Test
	void getReturnsReadyPromotionWithoutMutation() {
		MigrationRun run = run();
		MigrationPromotion promotion = promotion(run, PromotionStatus.READY);
		promotion.setEligibleBatches(1);
		promotion.setTotalBatches(1);
		promotion.setTotalRecords(3L);
		MigrationPromotionBatch batch = promotionBatch(batch(1664L, 1), PromotionBatchStatus.ELIGIBLE, 3, 0, 0);
		when(migrationRunRepository.existsById(runId)).thenReturn(true);
		when(promotionRepository.findByMigrationRun_IdOrderByCreatedAtDesc(runId)).thenReturn(List.of(promotion));
		when(promotionBatchRepository.findByPromotion_Id(promotionId)).thenReturn(List.of(batch));

		ExecutePromotionResponse response = service.get(runId);

		assertThat(response.promotionId()).isEqualTo(promotionId);
		assertThat(response.runId()).isEqualTo(runId);
		assertThat(response.status()).isEqualTo(PromotionStatus.READY);
		assertThat(response.eligibleBatches()).isEqualTo(1);
		assertThat(response.promotedBatches()).isZero();
		assertThat(response.batches()).singleElement().satisfies(item -> {
			assertThat(item.batchId()).isEqualTo(1664L);
			assertThat(item.promotionStatus()).isEqualTo(PromotionBatchStatus.ELIGIBLE);
			assertThat(item.eligibleRecordCount()).isEqualTo(3L);
			assertThat(item.promotedRecordCount()).isZero();
		});
		assertNoWrites();
	}

	@Test
	void getReturnsPromotedPromotion() {
		MigrationRun run = run();
		MigrationPromotion promotion = promotion(run, PromotionStatus.PROMOTED);
		promotion.setEligibleBatches(1);
		promotion.setPromotedBatches(1);
		promotion.setTotalBatches(1);
		promotion.setTotalRecords(3L);
		promotion.setPromotedRecords(3L);
		MigrationPromotionBatch batch = promotionBatch(batch(1664L, 1), PromotionBatchStatus.PROMOTED, 3, 3, 0);
		when(migrationRunRepository.existsById(runId)).thenReturn(true);
		when(promotionRepository.findByMigrationRun_IdOrderByCreatedAtDesc(runId)).thenReturn(List.of(promotion));
		when(promotionBatchRepository.findByPromotion_Id(promotionId)).thenReturn(List.of(batch));

		ExecutePromotionResponse response = service.get(runId);

		assertThat(response.status()).isEqualTo(PromotionStatus.PROMOTED);
		assertThat(response.promotedRecords()).isEqualTo(3L);
		assertThat(response.batches().getFirst().promotionStatus()).isEqualTo(PromotionBatchStatus.PROMOTED);
		assertNoWrites();
	}

	@Test
	void getReturnsPartialAndFailedBatchState() {
		MigrationRun run = run();
		MigrationPromotion promotion = promotion(run, PromotionStatus.PARTIALLY_PROMOTED);
		promotion.setTotalBatches(2);
		promotion.setEligibleBatches(2);
		promotion.setPromotedBatches(1);
		promotion.setFailedBatches(1);
		promotion.setPromotedRecords(1L);
		promotion.setFailedRecords(1L);
		MigrationPromotionBatch later = promotionBatch(batch(200L, 2), PromotionBatchStatus.FAILED, 1, 0, 1);
		later.setErrorCode("PROMOTION_BATCH_FAILED");
		later.setErrorMessage("unique violation");
		MigrationPromotionBatch earlier = promotionBatch(batch(100L, 1), PromotionBatchStatus.PROMOTED, 1, 1, 0);
		when(migrationRunRepository.existsById(runId)).thenReturn(true);
		when(promotionRepository.findByMigrationRun_IdOrderByCreatedAtDesc(runId)).thenReturn(List.of(promotion));
		when(promotionBatchRepository.findByPromotion_Id(promotionId)).thenReturn(List.of(later, earlier));

		ExecutePromotionResponse response = service.get(runId);

		assertThat(response.status()).isEqualTo(PromotionStatus.PARTIALLY_PROMOTED);
		assertThat(response.failedBatches()).isEqualTo(1);
		assertThat(response.batches()).extracting(item -> item.batchId()).containsExactly(100L, 200L);
		assertThat(response.batches().get(1).promotionStatus()).isEqualTo(PromotionBatchStatus.FAILED);
		assertThat(response.batches().get(1).errorCode()).isEqualTo("PROMOTION_BATCH_FAILED");
		assertNoWrites();
	}

	@Test
	void getUsesLatestPromotionWhenMultipleExist() {
		MigrationRun run = run();
		MigrationPromotion older = promotion(run, PromotionStatus.READY);
		older.setId(UUID.randomUUID());
		MigrationPromotion newest = promotion(run, PromotionStatus.PROMOTED);
		when(migrationRunRepository.existsById(runId)).thenReturn(true);
		when(promotionRepository.findByMigrationRun_IdOrderByCreatedAtDesc(runId)).thenReturn(List.of(newest, older));
		when(promotionBatchRepository.findByPromotion_Id(promotionId)).thenReturn(List.of());

		ExecutePromotionResponse response = service.get(runId);

		assertThat(response.promotionId()).isEqualTo(promotionId);
		assertThat(response.status()).isEqualTo(PromotionStatus.PROMOTED);
	}

	@Test
	void getThrowsWhenPromotionIsMissing() {
		when(migrationRunRepository.existsById(runId)).thenReturn(true);
		when(promotionRepository.findByMigrationRun_IdOrderByCreatedAtDesc(runId)).thenReturn(List.of());

		assertThatThrownBy(() -> service.get(runId))
				.isInstanceOf(PromotionNotFoundException.class)
				.hasMessage("Promotion not found for migration run " + runId);
		assertNoWrites();
	}

	@Test
	void getThrowsWhenRunIsMissing() {
		when(migrationRunRepository.existsById(runId)).thenReturn(false);

		assertThatThrownBy(() -> service.get(runId))
				.isInstanceOf(MigrationRunNotFoundException.class);
		verify(promotionRepository, never()).findByMigrationRun_IdOrderByCreatedAtDesc(any());
	}

	private void assertNoWrites() {
		verify(promotionRepository, never()).save(any());
		verify(promotionBatchRepository, never()).save(any());
		verify(migrationRunRepository, never()).save(any());
		verify(migrationRunRepository, never()).findByIdForUpdate(any());
	}

	private MigrationRun run() {
		MigrationRun run = new MigrationRun();
		run.setId(runId);
		return run;
	}

	private MigrationPromotion promotion(MigrationRun run, PromotionStatus status) {
		MigrationPromotion promotion = new MigrationPromotion();
		promotion.setId(promotionId);
		promotion.setMigrationRun(run);
		promotion.setStatus(status);
		return promotion;
	}

	private static MigrationPromotionBatch promotionBatch(
			MigrationBatch migrationBatch,
			PromotionBatchStatus status,
			long eligible,
			long promoted,
			long failed) {
		MigrationPromotionBatch batch = new MigrationPromotionBatch();
		batch.setMigrationBatch(migrationBatch);
		batch.setStatus(status);
		batch.setEligibleRecordCount(eligible);
		batch.setPromotedRecordCount(promoted);
		batch.setFailedRecordCount(failed);
		return batch;
	}

	private static MigrationBatch batch(long id, int number) {
		MigrationBatch batch = new MigrationBatch();
		try {
			Field field = MigrationBatch.class.getDeclaredField("id");
			field.setAccessible(true);
			field.set(batch, id);
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException(ex);
		}
		batch.setBatchNumber(number);
		return batch;
	}
}
