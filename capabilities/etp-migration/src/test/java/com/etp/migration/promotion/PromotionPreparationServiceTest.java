package com.etp.migration.promotion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.etp.migration.connector.target.InvalidPromotionConfigurationException;
import com.etp.migration.entity.MigrationBatch;
import com.etp.migration.entity.MigrationBatchStatus;
import com.etp.migration.entity.MigrationDefinition;
import com.etp.migration.entity.MigrationPromotion;
import com.etp.migration.entity.MigrationPromotionBatch;
import com.etp.migration.entity.MigrationReconciliation;
import com.etp.migration.entity.MigrationRun;
import com.etp.migration.entity.MigrationRunStatus;
import com.etp.migration.entity.PromotionBatchStatus;
import com.etp.migration.entity.PromotionStatus;
import com.etp.migration.entity.ReconciliationStatus;
import com.etp.migration.entity.ReconciliationType;
import com.etp.migration.processing.MigrationErrorCodes;
import com.etp.migration.promotion.dto.PreparePromotionResponse;
import com.etp.migration.repository.MigrationBatchRepository;
import com.etp.migration.repository.MigrationPromotionBatchRepository;
import com.etp.migration.repository.MigrationPromotionRepository;
import com.etp.migration.repository.MigrationReconciliationRepository;
import com.etp.migration.repository.MigrationRunRepository;
import com.etp.migration.run.MigrationRunNotFoundException;
import jakarta.persistence.EntityManager;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.data.jpa.repository.Query;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PromotionPreparationServiceTest {

	@Mock
	private MigrationRunRepository migrationRunRepository;
	@Mock
	private MigrationBatchRepository migrationBatchRepository;
	@Mock
	private MigrationReconciliationRepository reconciliationRepository;
	@Mock
	private MigrationPromotionRepository promotionRepository;
	@Mock
	private MigrationPromotionBatchRepository promotionBatchRepository;
	@Mock
	private StagingRecordCounter stagingRecordCounter;
	@Mock
	private EntityManager entityManager;

	private PromotionPreparationService service;
	private UUID runId;
	private final Map<Long, Long> stagedCounts = new HashMap<>();

	@BeforeEach
	void setUp() {
		service = new PromotionPreparationService(
				migrationRunRepository,
				migrationBatchRepository,
				reconciliationRepository,
				promotionRepository,
				promotionBatchRepository,
				stagingRecordCounter,
				entityManager);
		stagedCounts.clear();
		when(stagingRecordCounter.countStagedRecordsByBatch(any(), any())).thenAnswer(invocation -> Map.copyOf(stagedCounts));
		when(entityManager.getReference(eq(MigrationBatch.class), any())).thenAnswer(invocation -> {
			MigrationBatch reference = new MigrationBatch();
			setId(reference, invocation.getArgument(1));
			return reference;
		});
		runId = UUID.randomUUID();
		when(promotionRepository.save(any(MigrationPromotion.class))).thenAnswer(invocation -> {
			MigrationPromotion promotion = invocation.getArgument(0);
			if (promotion.getId() == null) {
				promotion.setId(UUID.randomUUID());
			}
			return promotion;
		});
	}

	@Test
	void batchHeaderQueryDoesNotReadSourceLocator() throws Exception {
		Query headers = MigrationBatchRepository.class
				.getMethod("findHeadersByMigrationRunId", UUID.class)
				.getAnnotation(Query.class);
		Query refs = com.etp.migration.repository.MigrationPromotionBatchRepository.class
				.getMethod("findRefsByPromotionAndStatus", UUID.class, PromotionBatchStatus.class)
				.getAnnotation(Query.class);
		assertThat(headers.value()).doesNotContain("sourceLocator");
		assertThat(refs.value()).doesNotContain("sourceLocator");
		assertThat(headers.value()).contains("b.id", "b.batchNumber", "b.status");
	}

	@Test
	void completedRunWithReconciliationIsReady() {
		MigrationBatch batch = batch(11L, MigrationBatchStatus.COMPLETED);
		stubReadyRun(MigrationRunStatus.COMPLETED, List.of(batch), enabledPromotion());
		stagedCounts.put(11L, 3L);

		PreparePromotionResponse response = service.prepare(runId, null);

		assertThat(response.status()).isEqualTo(PromotionStatus.READY);
		assertThat(response.eligibleBatches()).isEqualTo(1);
		assertThat(response.eligibleRecords()).isEqualTo(3L);
		verify(migrationBatchRepository, never()).findByMigrationRun_IdOrderByBatchNumberAsc(any());
		assertThat(response.batches()).singleElement().satisfies(item -> {
			assertThat(item.executionStatus()).isEqualTo(MigrationBatchStatus.COMPLETED);
			assertThat(item.promotionStatus()).isEqualTo(PromotionBatchStatus.ELIGIBLE);
			assertThat(item.eligibleRecordCount()).isEqualTo(3L);
		});
	}

	@Test
	void completedWithErrorsRunWithReconciliationIsReady() {
		MigrationBatch batch = batch(12L, MigrationBatchStatus.COMPLETED_WITH_ERRORS);
		stubReadyRun(MigrationRunStatus.COMPLETED_WITH_ERRORS, List.of(batch), enabledPromotion());
		stagedCounts.put(12L, 2L);

		PreparePromotionResponse response = service.prepare(runId, null);

		assertThat(response.status()).isEqualTo(PromotionStatus.READY);
		assertThat(response.batches().getFirst().executionStatus()).isEqualTo(MigrationBatchStatus.COMPLETED_WITH_ERRORS);
		assertThat(response.batches().getFirst().promotionStatus()).isEqualTo(PromotionBatchStatus.ELIGIBLE);
	}

	@Test
	void failedRunIsRejected() {
		stubRunOnly(MigrationRunStatus.FAILED, enabledPromotion());
		assertRejected(MigrationErrorCodes.PROMOTION_RUN_NOT_COMPLETE);
	}

	@Test
	void runningRunIsRejected() {
		stubRunOnly(MigrationRunStatus.RUNNING, enabledPromotion());
		assertRejected(MigrationErrorCodes.PROMOTION_RUN_NOT_COMPLETE);
	}

	@Test
	void claimedBatchIsRejected() {
		stubReadyRun(MigrationRunStatus.COMPLETED, List.of(), enabledPromotion());
		when(migrationBatchRepository.countByMigrationRun_IdAndStatusIn(eq(runId), any())).thenReturn(1L);
		assertRejected(MigrationErrorCodes.PROMOTION_ACTIVE_BATCHES);
	}

	@Test
	void processingBatchIsRejected() {
		stubReadyRun(MigrationRunStatus.COMPLETED, List.of(), enabledPromotion());
		when(migrationBatchRepository.countByMigrationRun_IdAndStatusIn(eq(runId), any())).thenReturn(1L);
		assertRejected(MigrationErrorCodes.PROMOTION_ACTIVE_BATCHES);
	}

	@Test
	void retryPendingBatchIsRejected() {
		stubReadyRun(MigrationRunStatus.COMPLETED, List.of(), enabledPromotion());
		when(migrationBatchRepository.countByMigrationRun_IdAndStatusIn(eq(runId), any())).thenReturn(1L);
		assertRejected(MigrationErrorCodes.PROMOTION_ACTIVE_BATCHES);
	}

	@Test
	void completedBatchWithStagedRowsIsEligible() {
		completedRunWithReconciliationIsReady();
	}

	@Test
	void completedWithErrorsBatchWithStagedRowsIsEligible() {
		completedWithErrorsRunWithReconciliationIsReady();
	}

	@Test
	void completedBatchWithZeroStagedRowsIsNotEligible() {
		MigrationBatch batch = batch(13L, MigrationBatchStatus.COMPLETED);
		stubReadyRun(MigrationRunStatus.COMPLETED, List.of(batch), enabledPromotion());
		stagedCounts.put(13L, 0L);

		PreparePromotionResponse response = service.prepare(runId, null);

		assertThat(response.status()).isEqualTo(PromotionStatus.READY);
		assertThat(response.eligibleBatches()).isZero();
		assertThat(response.skippedBatches()).isEqualTo(1);
		assertThat(response.batches().getFirst().promotionStatus()).isEqualTo(PromotionBatchStatus.NOT_ELIGIBLE);
		assertThat(response.batches().getFirst().eligibleRecordCount()).isZero();
		ArgumentCaptor<MigrationPromotionBatch> captor = ArgumentCaptor.forClass(MigrationPromotionBatch.class);
		verify(promotionBatchRepository).save(captor.capture());
		assertThat(captor.getValue().getStatus()).isEqualTo(PromotionBatchStatus.NOT_ELIGIBLE);
		assertThat(captor.getValue().getSkippedRecordCount()).isZero();
	}

	@Test
	void failedBatchIsNotEligible() {
		MigrationBatch failed = batch(14L, MigrationBatchStatus.FAILED);
		MigrationBatch completed = batch(15L, MigrationBatchStatus.COMPLETED);
		stubReadyRun(MigrationRunStatus.COMPLETED_WITH_ERRORS, List.of(failed, completed), enabledPromotion());
		stagedCounts.put(14L, 0L);
		stagedCounts.put(15L, 4L);

		PreparePromotionResponse response = service.prepare(runId, null);

		assertThat(response.batches().getFirst().promotionStatus()).isEqualTo(PromotionBatchStatus.NOT_ELIGIBLE);
		assertThat(response.batches().get(1).promotionStatus()).isEqualTo(PromotionBatchStatus.ELIGIBLE);
		assertThat(response.eligibleBatches()).isEqualTo(1);
		assertThat(response.skippedBatches()).isEqualTo(1);
	}

	@Test
	void validationFailuresAreCountedOnlyThroughStagingRows() {
		MigrationBatch batch = batch(16L, MigrationBatchStatus.COMPLETED_WITH_ERRORS);
		batch.setValidationFailureCount(5);
		batch.setRecordCount(7);
		stubReadyRun(MigrationRunStatus.COMPLETED_WITH_ERRORS, List.of(batch), enabledPromotion());
		stagedCounts.put(16L, 2L);

		PreparePromotionResponse response = service.prepare(runId, null);

		assertThat(response.eligibleRecords()).isEqualTo(2L);
		assertThat(response.totalRecords()).isEqualTo(2L);
	}

	@Test
	void existingPromotingPromotionIsRejected() {
		MigrationRun run = stubReadyRun(MigrationRunStatus.COMPLETED, List.of(), enabledPromotion());
		MigrationPromotion promoting = new MigrationPromotion();
		promoting.setId(UUID.randomUUID());
		promoting.setMigrationRun(run);
		promoting.setStatus(PromotionStatus.PROMOTING);
		when(promotionRepository.findByMigrationRun_IdOrderByCreatedAtDesc(runId)).thenReturn(List.of(promoting));

		assertRejected(MigrationErrorCodes.PROMOTION_IN_PROGRESS);
		verify(promotionRepository, never()).save(any());
	}

	@Test
	void reservedNotStartedPromotionIsFilledWithoutASecondRow() {
		MigrationBatch batch = batch(21L, MigrationBatchStatus.COMPLETED);
		MigrationRun run = stubReadyRun(MigrationRunStatus.COMPLETED, List.of(batch), enabledPromotion());
		UUID reservedId = UUID.randomUUID();
		MigrationPromotion reserved = new MigrationPromotion();
		reserved.setId(reservedId);
		reserved.setMigrationRun(run);
		reserved.setStatus(PromotionStatus.NOT_STARTED);
		reserved.setRequestedBy("operator");
		when(promotionRepository.findByMigrationRun_IdOrderByCreatedAtDesc(runId)).thenReturn(List.of(reserved));
		stagedCounts.put(21L, 4L);

		PreparePromotionResponse response = service.prepare(runId, null);

		assertThat(response.promotionId()).isEqualTo(reservedId);
		assertThat(response.status()).isEqualTo(PromotionStatus.READY);
		assertThat(response.eligibleRecords()).isEqualTo(4L);
		verify(promotionRepository, never()).save(any());
	}

	@Test
	void reserveCreatesOneNotStartedPromotion() {
		MigrationRun run = stubRunOnly(MigrationRunStatus.COMPLETED, enabledPromotion());
		when(promotionRepository.findByMigrationRun_IdOrderByCreatedAtDesc(runId)).thenReturn(List.of());
		when(promotionRepository.saveAndFlush(any(MigrationPromotion.class))).thenAnswer(invocation -> {
			MigrationPromotion promotion = invocation.getArgument(0);
			promotion.setId(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));
			return promotion;
		});

		UUID reserved = service.reserve(runId, "operator");

		assertThat(reserved).isEqualTo(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));
		ArgumentCaptor<MigrationPromotion> captor = ArgumentCaptor.forClass(MigrationPromotion.class);
		verify(promotionRepository).saveAndFlush(captor.capture());
		assertThat(captor.getValue().getStatus()).isEqualTo(PromotionStatus.NOT_STARTED);
		assertThat(captor.getValue().getMigrationRun()).isSameAs(run);
		assertThat(captor.getValue().getRequestedBy()).isEqualTo("operator");
	}

	@Test
	void reserveReusesAnExistingReadyPromotion() {
		MigrationRun run = stubRunOnly(MigrationRunStatus.COMPLETED, enabledPromotion());
		MigrationPromotion ready = new MigrationPromotion();
		ready.setId(UUID.randomUUID());
		ready.setMigrationRun(run);
		ready.setStatus(PromotionStatus.READY);
		when(promotionRepository.findByMigrationRun_IdOrderByCreatedAtDesc(runId)).thenReturn(List.of(ready));

		assertThat(service.reserve(runId, "operator")).isEqualTo(ready.getId());
		verify(promotionRepository, never()).saveAndFlush(any());
	}

	@Test
	void existingPromotedPromotionIsReturnedWithoutDuplicate() {
		MigrationRun run = stubReadyRun(MigrationRunStatus.COMPLETED, List.of(batch(17L, MigrationBatchStatus.COMPLETED)), enabledPromotion());
		MigrationPromotion promoted = new MigrationPromotion();
		promoted.setId(UUID.randomUUID());
		promoted.setMigrationRun(run);
		promoted.setStatus(PromotionStatus.PROMOTED);
		promoted.setTotalBatches(1);
		promoted.setEligibleBatches(1);
		promoted.setTotalRecords(3L);
		when(promotionRepository.findByMigrationRun_IdOrderByCreatedAtDesc(runId)).thenReturn(List.of(promoted));
		when(promotionBatchRepository.findHeadersByPromotionId(promoted.getId())).thenReturn(List.of(
				new PromotionBatchHeader(17L, PromotionBatchStatus.PROMOTED, 3L, 0L)));

		PreparePromotionResponse response = service.prepare(runId, null);

		assertThat(response.promotionId()).isEqualTo(promoted.getId());
		assertThat(response.status()).isEqualTo(PromotionStatus.PROMOTED);
		verify(promotionRepository, never()).save(any());
	}

	@Test
	void partiallyPromotedAllowsRetryWithoutDuplicatingPromotedBatches() {
		MigrationBatch first = batch(18L, MigrationBatchStatus.COMPLETED);
		MigrationBatch second = batch(19L, MigrationBatchStatus.COMPLETED);
		MigrationRun run = stubReadyRun(MigrationRunStatus.COMPLETED, List.of(first, second), enabledPromotion());
		MigrationPromotion previous = new MigrationPromotion();
		previous.setId(UUID.randomUUID());
		previous.setMigrationRun(run);
		previous.setStatus(PromotionStatus.PARTIALLY_PROMOTED);
		when(promotionRepository.findByMigrationRun_IdOrderByCreatedAtDesc(runId)).thenReturn(List.of(previous));
		when(promotionBatchRepository.findBatchIdsByRunAndStatus(runId, PromotionBatchStatus.PROMOTED))
				.thenReturn(List.of(18L));
		stagedCounts.put(18L, 3L);
		stagedCounts.put(19L, 4L);

		PreparePromotionResponse response = service.prepare(runId, null);

		assertThat(response.status()).isEqualTo(PromotionStatus.READY);
		assertThat(response.eligibleBatches()).isEqualTo(1);
		assertThat(response.skippedBatches()).isEqualTo(1);
		assertThat(response.batches().getFirst().promotionStatus()).isEqualTo(PromotionBatchStatus.PROMOTED);
		assertThat(response.batches().get(1).promotionStatus()).isEqualTo(PromotionBatchStatus.ELIGIBLE);
		ArgumentCaptor<MigrationPromotionBatch> captor = ArgumentCaptor.forClass(MigrationPromotionBatch.class);
		verify(promotionBatchRepository).save(captor.capture());
		assertThat(captor.getAllValues()).hasSize(1);
		assertThat(captor.getValue().getMigrationBatch().getId()).isEqualTo(19L);
		assertThat(captor.getValue().getStatus()).isEqualTo(PromotionBatchStatus.ELIGIBLE);
	}

	@Test
	void missingReconciliationIsRejected() {
		stubRunOnly(MigrationRunStatus.COMPLETED, enabledPromotion());
		when(migrationBatchRepository.countByMigrationRun_IdAndStatusIn(eq(runId), any())).thenReturn(0L);
		when(reconciliationRepository.findByMigrationRun_Id(runId)).thenReturn(List.of());
		assertRejected(MigrationErrorCodes.PROMOTION_RECONCILIATION_REQUIRED);
	}

	@Test
	void passedWithWarningsReconciliationIsAccepted() {
		MigrationBatch batch = batch(20L, MigrationBatchStatus.COMPLETED);
		MigrationReconciliation reconciliation = new MigrationReconciliation();
		reconciliation.setReconciliationType(ReconciliationType.FULL);
		reconciliation.setStatus(ReconciliationStatus.PASSED_WITH_WARNINGS);
		stubReadyRun(MigrationRunStatus.COMPLETED, List.of(batch), enabledPromotion(), List.of(reconciliation));
		stagedCounts.put(20L, 1L);

		PreparePromotionResponse response = service.prepare(runId, null);

		assertThat(response.status()).isEqualTo(PromotionStatus.READY);
	}

	@Test
	void promotionDisabledIsRejected() {
		stubRunOnly(MigrationRunStatus.COMPLETED, Map.of("promotion", Map.of("enabled", false)));
		assertThatThrownBy(() -> service.prepare(runId, null))
				.isInstanceOf(PromotionException.class)
				.extracting(ex -> ((PromotionException) ex).errorCode())
				.isEqualTo(MigrationErrorCodes.PROMOTION_DISABLED);
	}

	@Test
	void invalidPromotionConfigurationIsRejected() {
		Map<String, Object> configuration = new HashMap<>(enabledPromotion());
		Map<String, Object> promotion = new HashMap<>((Map<String, Object>) configuration.get("promotion"));
		promotion.remove("schema");
		configuration.put("promotion", promotion);
		stubRunOnly(MigrationRunStatus.COMPLETED, configuration);
		assertThatThrownBy(() -> service.prepare(runId, null))
				.isInstanceOf(InvalidPromotionConfigurationException.class);
	}

	@Test
	void missingRunIsRejected() {
		when(migrationRunRepository.findByIdForUpdate(runId)).thenReturn(Optional.empty());
		assertThatThrownBy(() -> service.prepare(runId, null))
				.isInstanceOf(MigrationRunNotFoundException.class);
	}

	private void assertRejected(String code) {
		assertThatThrownBy(() -> service.prepare(runId, null))
				.isInstanceOf(PromotionException.class)
				.satisfies(ex -> assertThat(((PromotionException) ex).errorCode()).isEqualTo(code));
	}

	private MigrationRun stubReadyRun(
			MigrationRunStatus status,
			List<MigrationBatch> batches,
			Map<String, Object> configuration) {
		return stubReadyRun(status, batches, configuration, List.of(new MigrationReconciliation()));
	}

	private MigrationRun stubReadyRun(
			MigrationRunStatus status,
			List<MigrationBatch> batches,
			Map<String, Object> configuration,
			List<MigrationReconciliation> reconciliations) {
		MigrationRun run = stubRunOnly(status, configuration);
		when(migrationBatchRepository.countByMigrationRun_IdAndStatusIn(eq(runId), any())).thenReturn(0L);
		when(reconciliationRepository.findByMigrationRun_Id(runId)).thenReturn(reconciliations);
		when(promotionRepository.findByMigrationRun_IdOrderByCreatedAtDesc(runId)).thenReturn(List.of());
		when(promotionBatchRepository.findBatchIdsByRunAndStatus(runId, PromotionBatchStatus.PROMOTED)).thenReturn(List.of());
		when(migrationBatchRepository.findHeadersByMigrationRunId(runId)).thenReturn(batches.stream()
				.map(batch -> new BatchHeader(batch.getId(), batch.getBatchNumber(), batch.getStatus()))
				.toList());
		return run;
	}

	private MigrationRun stubRunOnly(MigrationRunStatus status, Map<String, Object> configuration) {
		MigrationDefinition definition = new MigrationDefinition();
		definition.setId(UUID.randomUUID());
		definition.setTargetType("POSTGRESQL");
		definition.setConfiguration(new HashMap<>(configuration));
		MigrationRun run = new MigrationRun();
		run.setId(runId);
		run.setMigrationDefinition(definition);
		run.setStatus(status);
		when(migrationRunRepository.findByIdForUpdate(runId)).thenReturn(Optional.of(run));
		return run;
	}

	private static Map<String, Object> enabledPromotion() {
		Map<String, Object> promotion = new LinkedHashMap<>();
		promotion.put("enabled", true);
		promotion.put("schema", "uam");
		promotion.put("table", "address");
		promotion.put("strategy", "INSERT");
		promotion.put("columns", Map.of("external_id", "external_id"));
		return Map.of(
				"target", Map.of("schema", "migration_staging", "stagingTable", "address_load", "mergeStrategy", "NONE"),
				"promotion", promotion);
	}

	private static MigrationBatch batch(long id, MigrationBatchStatus status) {
		MigrationBatch batch = new MigrationBatch();
		setId(batch, id);
		batch.setStatus(status);
		batch.setBatchNumber((int) id);
		return batch;
	}

	private static void setId(MigrationBatch batch, long id) {
		try {
			Field field = MigrationBatch.class.getDeclaredField("id");
			field.setAccessible(true);
			field.set(batch, id);
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
