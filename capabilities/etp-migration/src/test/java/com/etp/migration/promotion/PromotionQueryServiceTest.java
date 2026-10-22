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
import com.etp.migration.repository.MigrationPromotionBatchRepository.PromotionBatchStatusCount;
import com.etp.migration.repository.MigrationPromotionRepository;
import com.etp.migration.repository.MigrationRunRepository;
import com.etp.migration.run.MigrationRunNotFoundException;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;

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
		service = new PromotionQueryService(migrationRunRepository, promotionRepository, promotionBatchRepository, null);
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
		when(promotionBatchRepository.summarize(promotionId)).thenReturn(List.of(count(PromotionBatchStatus.ELIGIBLE, 1, 3)));

		ExecutePromotionResponse response = service.get(runId);

		assertThat(response.promotionId()).isEqualTo(promotionId);
		assertThat(response.runId()).isEqualTo(runId);
		assertThat(response.status()).isEqualTo(PromotionStatus.READY);
		assertThat(response.eligibleBatches()).isEqualTo(1);
		assertThat(response.promotedBatches()).isZero();
		assertThat(response.eligibleRecords()).isEqualTo(3L);
		assertThat(response.batchCounts()).containsEntry("ELIGIBLE", 1L);
		assertThat(response.batches()).isEmpty();
		verify(promotionBatchRepository, never()).findByPromotion_Id(any());
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
		when(promotionBatchRepository.summarize(promotionId)).thenReturn(List.of(count(PromotionBatchStatus.PROMOTED, 1, 3)));

		ExecutePromotionResponse response = service.get(runId);

		assertThat(response.status()).isEqualTo(PromotionStatus.PROMOTED);
		assertThat(response.promotedRecords()).isEqualTo(3L);
		assertThat(response.batchCounts()).containsEntry("PROMOTED", 1L);
		assertThat(response.batches()).isEmpty();
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
		promotion.setErrorCode("MIGRATION_PROMOTION_PARTIAL_FAILURE");
		promotion.setErrorMessage("Promotion completed with one or more failed batches");
		MigrationPromotionBatch later = promotionBatch(batch(200L, 2), PromotionBatchStatus.FAILED, 1, 0, 1);
		later.setErrorCode("PROMOTION_BATCH_FAILED");
		later.setErrorMessage("unique violation");
		MigrationPromotionBatch earlier = promotionBatch(batch(100L, 1), PromotionBatchStatus.PROMOTED, 1, 1, 0);
		when(migrationRunRepository.existsById(runId)).thenReturn(true);
		when(promotionRepository.findByMigrationRun_IdOrderByCreatedAtDesc(runId)).thenReturn(List.of(promotion));
		when(promotionBatchRepository.summarize(promotionId)).thenReturn(List.of(
				count(PromotionBatchStatus.PROMOTED, 1, 1),
				count(PromotionBatchStatus.FAILED, 1, 1)));

		ExecutePromotionResponse response = service.get(runId);

		assertThat(response.status()).isEqualTo(PromotionStatus.PARTIALLY_PROMOTED);
		assertThat(response.errorCode()).isEqualTo("MIGRATION_PROMOTION_PARTIAL_FAILURE");
		assertThat(response.errorMessage()).isEqualTo("Promotion completed with one or more failed batches");
		assertThat(response.failedBatches()).isEqualTo(1);
		assertThat(response.promotedBatches()).isEqualTo(1);
		assertThat(response.batchCounts()).containsEntry("FAILED", 1L).containsEntry("PROMOTED", 1L);
		assertThat(response.batches()).isEmpty();
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
		when(promotionBatchRepository.summarize(promotionId)).thenReturn(List.of());

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
	void listBatchesReportsTheLatestAttemptDuration() {
		MigrationRun run = run();
		MigrationPromotion promotion = promotion(run, PromotionStatus.PROMOTING);
		Instant started = Instant.parse("2026-10-22T01:00:00Z");
		Instant completed = Instant.parse("2026-10-22T01:00:03Z");
		PromotionBatchRow finished = new PromotionBatchRow(
				1664L, 1, PromotionBatchStatus.FAILED, 3L, 0L, 3L, 0L,
				started, completed, "MIGRATION_PROMOTION_BATCH_FAILED", "parent missing");
		PromotionBatchRow active = new PromotionBatchRow(
				1665L, 2, PromotionBatchStatus.PROMOTING, 3L, 0L, 0L, 0L,
				started, null, null, null);
		when(migrationRunRepository.existsById(runId)).thenReturn(true);
		when(promotionRepository.findByMigrationRun_IdOrderByCreatedAtDesc(runId)).thenReturn(List.of(promotion));
		when(promotionBatchRepository.pageByRun(org.mockito.ArgumentMatchers.eq(runId), org.mockito.ArgumentMatchers.any()))
				.thenReturn(new PageImpl<>(List.of(finished, active)));

		var page = service.listBatches(runId, 0, 20, " ");

		assertThat(page.content().get(0).durationMillis()).isEqualTo(3_000L);
		assertThat(page.content().get(0).completedAt()).isEqualTo(completed);
		assertThat(page.content().get(0).errorMessage()).isEqualTo("parent missing");
		assertThat(page.content().get(1).completedAt()).isNull();
		assertThat(page.content().get(1).durationMillis()).isNull();
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

	private static PromotionBatchStatusCount count(PromotionBatchStatus status, long batches, long eligibleRecords) {
		return new PromotionBatchStatusCount() {
			@Override
			public PromotionBatchStatus getStatus() {
				return status;
			}

			@Override
			public long getBatches() {
				return batches;
			}

			@Override
			public long getEligibleRecords() {
				return eligibleRecords;
			}
		};
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
