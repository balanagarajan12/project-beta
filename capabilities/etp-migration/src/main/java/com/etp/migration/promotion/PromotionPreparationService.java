package com.etp.migration.promotion;

import com.etp.migration.config.AuditActor;
import com.etp.migration.entity.MigrationBatch;
import com.etp.migration.entity.MigrationBatchStatus;
import com.etp.migration.entity.MigrationDefinition;
import com.etp.migration.entity.MigrationPromotion;
import com.etp.migration.entity.MigrationPromotionBatch;
import com.etp.migration.entity.MigrationRun;
import com.etp.migration.entity.MigrationRunStatus;
import com.etp.migration.entity.PromotionBatchStatus;
import com.etp.migration.entity.PromotionStatus;
import com.etp.migration.processing.MigrationErrorCodes;
import com.etp.migration.promotion.dto.PreparePromotionRequest;
import com.etp.migration.promotion.dto.PreparePromotionResponse;
import com.etp.migration.promotion.dto.PromotionBatchEligibilityResponse;
import com.etp.migration.repository.MigrationBatchRepository;
import com.etp.migration.repository.MigrationPromotionBatchRepository;
import com.etp.migration.repository.MigrationPromotionRepository;
import com.etp.migration.repository.MigrationReconciliationRepository;
import com.etp.migration.repository.MigrationRunRepository;
import com.etp.migration.run.MigrationRunNotFoundException;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PromotionPreparationService {

	private static final String POSTGRESQL_TARGET = "POSTGRESQL";
	private static final EnumSet<MigrationRunStatus> EXECUTION_COMPLETE = EnumSet.of(
			MigrationRunStatus.COMPLETED,
			MigrationRunStatus.COMPLETED_WITH_ERRORS);
	private static final EnumSet<MigrationBatchStatus> ACTIVE_EXECUTION = EnumSet.of(
			MigrationBatchStatus.CLAIMED,
			MigrationBatchStatus.PROCESSING,
			MigrationBatchStatus.RETRY_PENDING);
	private static final EnumSet<MigrationBatchStatus> EXECUTION_ELIGIBLE = EnumSet.of(
			MigrationBatchStatus.COMPLETED,
			MigrationBatchStatus.COMPLETED_WITH_ERRORS);

	private final MigrationRunRepository migrationRunRepository;
	private final MigrationBatchRepository migrationBatchRepository;
	private final MigrationReconciliationRepository reconciliationRepository;
	private final MigrationPromotionRepository promotionRepository;
	private final MigrationPromotionBatchRepository promotionBatchRepository;
	private final StagingRecordCounter stagingRecordCounter;
	private final EntityManager entityManager;

	public PromotionPreparationService(
			MigrationRunRepository migrationRunRepository,
			MigrationBatchRepository migrationBatchRepository,
			MigrationReconciliationRepository reconciliationRepository,
			MigrationPromotionRepository promotionRepository,
			MigrationPromotionBatchRepository promotionBatchRepository,
			StagingRecordCounter stagingRecordCounter,
			EntityManager entityManager) {
		this.migrationRunRepository = migrationRunRepository;
		this.migrationBatchRepository = migrationBatchRepository;
		this.reconciliationRepository = reconciliationRepository;
		this.promotionRepository = promotionRepository;
		this.promotionBatchRepository = promotionBatchRepository;
		this.stagingRecordCounter = stagingRecordCounter;
		this.entityManager = entityManager;
	}

	/**
	 * Returns the promotion this preparation will fill. A new row is {@code NOT_STARTED}
	 * until {@link #prepare} classifies batches. The caller holds the run lock.
	 */
	@Transactional
	public UUID reserve(UUID runId, String requestedBy) {
		MigrationRun run = migrationRunRepository.findByIdForUpdate(runId)
				.orElseThrow(() -> new MigrationRunNotFoundException(runId));
		List<MigrationPromotion> existing = promotionRepository.findByMigrationRun_IdOrderByCreatedAtDesc(runId);
		if (existing.stream().anyMatch(promotion -> promotion.getStatus() == PromotionStatus.PROMOTING)) {
			throw conflict(MigrationErrorCodes.PROMOTION_IN_PROGRESS, "A promotion is already in progress for this run");
		}
		for (PromotionStatus status : List.of(PromotionStatus.PROMOTED, PromotionStatus.READY, PromotionStatus.NOT_STARTED)) {
			for (MigrationPromotion promotion : existing) {
				if (promotion.getStatus() == status) {
					return promotion.getId();
				}
			}
		}
		Instant now = Instant.now();
		MigrationPromotion promotion = new MigrationPromotion();
		promotion.setMigrationRun(run);
		promotion.setStatus(PromotionStatus.NOT_STARTED);
		promotion.setRequestedBy(requestedBy == null ? AuditActor.current() : requestedBy);
		promotion.setRequestedAt(now);
		promotion.setPromotedBatches(0);
		promotion.setFailedBatches(0);
		promotion.setPromotedRecords(0L);
		promotion.setFailedRecords(0L);
		promotionRepository.saveAndFlush(promotion);
		return promotion.getId();
	}

	@Transactional(readOnly = true)
	public void assertEligible(UUID runId) {
		MigrationRun run = migrationRunRepository.findById(runId)
				.orElseThrow(() -> new MigrationRunNotFoundException(runId));
		Map<String, Object> configuration = run.getMigrationDefinition().getConfiguration() == null
				? Map.of()
				: run.getMigrationDefinition().getConfiguration();
		requirePostgresqlTarget(run.getMigrationDefinition().getTargetType());
		PromotionConfigurationGate.requireEnabled(configuration);
		requireExecutionComplete(run);
		requireNoActiveBatches(runId);
		requireReconciliation(runId);
		boolean promoting = promotionRepository.findByMigrationRun_IdOrderByCreatedAtDesc(runId).stream()
				.anyMatch(promotion -> promotion.getStatus() == PromotionStatus.PROMOTING);
		if (promoting) {
			throw conflict(MigrationErrorCodes.PROMOTION_IN_PROGRESS, "A promotion is already in progress for this run");
		}
	}

	@Transactional
	public PreparePromotionResponse prepare(UUID runId, PreparePromotionRequest request) {
		return prepare(runId, request, () -> { });
	}

	@Transactional
	public PreparePromotionResponse prepare(UUID runId, PreparePromotionRequest request, Runnable progress) {
		return prepare(runId, request, progress, null);
	}

	@Transactional
	public PreparePromotionResponse prepare(
			UUID runId,
			PreparePromotionRequest request,
			Runnable progress,
			String requestedBy) {
		if (progress != null) {
			progress.run();
		}
		MigrationRun run = migrationRunRepository.findByIdForUpdate(runId)
				.orElseThrow(() -> new MigrationRunNotFoundException(runId));
		MigrationDefinition definition = run.getMigrationDefinition();
		Map<String, Object> configuration = definition.getConfiguration() == null
				? Map.of()
				: definition.getConfiguration();

		requirePostgresqlTarget(definition.getTargetType());
		PromotionConfigurationGate.requireEnabled(configuration);
		requireExecutionComplete(run);
		requireNoActiveBatches(runId);
		requireReconciliation(runId);

		List<MigrationPromotion> existing = promotionRepository.findByMigrationRun_IdOrderByCreatedAtDesc(runId);
		if (existing.stream().anyMatch(promotion -> promotion.getStatus() == PromotionStatus.PROMOTING)) {
			throw conflict(MigrationErrorCodes.PROMOTION_IN_PROGRESS, "A promotion is already in progress for this run");
		}
		MigrationPromotion promoted = existing.stream()
				.filter(promotion -> promotion.getStatus() == PromotionStatus.PROMOTED)
				.findFirst()
				.orElse(null);
		if (promoted != null) {
			return toResponse(promoted, loadEligibility(promoted, runId));
		}
		MigrationPromotion active = existing.stream()
				.filter(promotion -> promotion.getStatus() == PromotionStatus.READY
						|| promotion.getStatus() == PromotionStatus.PROMOTING)
				.findFirst()
				.orElse(null);
		if (active != null) {
			return toResponse(active, loadEligibility(active, runId));
		}
		MigrationPromotion reserved = existing.stream()
				.filter(promotion -> promotion.getStatus() == PromotionStatus.NOT_STARTED)
				.findFirst()
				.orElse(null);

		Set<Long> alreadyPromotedBatchIds = alreadyPromotedBatchIds(runId);
		List<BatchHeader> batches = migrationBatchRepository.findHeadersByMigrationRunId(runId);
		Map<Long, Long> stagedByBatch = stagingRecordCounter.countStagedRecordsByBatch(runId, configuration);
		Instant now = Instant.now();
		MigrationPromotion promotion = reserved == null ? new MigrationPromotion() : reserved;
		if (reserved == null) {
			promotion.setMigrationRun(run);
		}
		promotion.setStatus(PromotionStatus.READY);
		if (promotion.getRequestedBy() == null) {
			promotion.setRequestedBy(requestedBy == null ? AuditActor.current() : requestedBy);
		}
		if (promotion.getRequestedAt() == null) {
			promotion.setRequestedAt(now);
		}
		promotion.setPromotedBatches(0);
		promotion.setFailedBatches(0);
		promotion.setPromotedRecords(0L);
		promotion.setFailedRecords(0L);
		if (reserved == null) {
			promotionRepository.save(promotion);
		}

		List<PromotionBatchEligibilityResponse> details = new ArrayList<>();
		int eligibleBatches = 0;
		int skippedBatches = 0;
		long eligibleRecords = 0L;
		long skippedRecords = 0L;
		for (BatchHeader batch : batches) {
			long staged = stagedByBatch.getOrDefault(batch.id(), 0L);
			boolean alreadyPromoted = alreadyPromotedBatchIds.contains(batch.id());
			boolean executionEligible = EXECUTION_ELIGIBLE.contains(batch.status());
			PromotionBatchStatus promotionStatus;
			long eligibleRecordCount;
			long skippedRecordCount;
			if (alreadyPromoted) {
				promotionStatus = PromotionBatchStatus.PROMOTED;
				eligibleRecordCount = 0L;
				skippedRecordCount = staged;
				skippedBatches++;
				skippedRecords += staged;
			} else if (executionEligible && staged > 0) {
				promotionStatus = PromotionBatchStatus.ELIGIBLE;
				eligibleRecordCount = staged;
				skippedRecordCount = 0L;
				eligibleBatches++;
				eligibleRecords += staged;
			} else {
				promotionStatus = PromotionBatchStatus.NOT_ELIGIBLE;
				eligibleRecordCount = 0L;
				skippedRecordCount = 0L;
				skippedBatches++;
			}
			if (progress != null) {
				progress.run();
			}
			if (!alreadyPromoted) {
				MigrationPromotionBatch promotionBatch = new MigrationPromotionBatch();
				promotionBatch.setPromotion(promotion);
				promotionBatch.setMigrationRun(run);
				promotionBatch.setMigrationBatch(entityManager.getReference(MigrationBatch.class, batch.id()));
				promotionBatch.setStatus(promotionStatus);
				promotionBatch.setEligibleRecordCount(eligibleRecordCount);
				promotionBatch.setSkippedRecordCount(skippedRecordCount);
				promotionBatch.setPromotedRecordCount(0L);
				promotionBatch.setFailedRecordCount(0L);
				promotionBatchRepository.save(promotionBatch);
			}
			details.add(new PromotionBatchEligibilityResponse(
					batch.id(),
					batch.status(),
					promotionStatus,
					eligibleRecordCount,
					skippedRecordCount));
		}

		promotion.setTotalBatches(batches.size());
		promotion.setEligibleBatches(eligibleBatches);
		promotion.setSkippedBatches(skippedBatches);
		promotion.setTotalRecords(eligibleRecords + skippedRecords);
		promotion.setSkippedRecords(skippedRecords);
		return toResponse(promotion, details, eligibleRecords);
	}

	private static void requirePostgresqlTarget(String targetType) {
		if (targetType == null || !POSTGRESQL_TARGET.equals(targetType.toUpperCase(Locale.ROOT))) {
			throw new PromotionException(
					HttpStatus.BAD_REQUEST.value(),
					MigrationErrorCodes.PROMOTION_UNSUPPORTED_TARGET,
					"Promotion is only supported for PostgreSQL targets");
		}
	}

	private static void requireExecutionComplete(MigrationRun run) {
		if (!EXECUTION_COMPLETE.contains(run.getStatus())) {
			throw conflict(
					MigrationErrorCodes.PROMOTION_RUN_NOT_COMPLETE,
					"Migration run is not execution-complete");
		}
	}

	private void requireNoActiveBatches(UUID runId) {
		long active = migrationBatchRepository.countByMigrationRun_IdAndStatusIn(runId, ACTIVE_EXECUTION);
		if (active > 0) {
			throw conflict(
					MigrationErrorCodes.PROMOTION_ACTIVE_BATCHES,
					"Migration run still has active execution batches");
		}
	}

	private void requireReconciliation(UUID runId) {
		if (reconciliationRepository.findByMigrationRun_Id(runId).isEmpty()) {
			throw conflict(
					MigrationErrorCodes.PROMOTION_RECONCILIATION_REQUIRED,
					"Reconciliation has not been performed for this run");
		}
	}

	private Set<Long> alreadyPromotedBatchIds(UUID runId) {
		return new HashSet<>(promotionBatchRepository.findBatchIdsByRunAndStatus(runId, PromotionBatchStatus.PROMOTED));
	}

	private List<PromotionBatchEligibilityResponse> loadEligibility(MigrationPromotion promotion, UUID runId) {
		Map<Long, PromotionBatchHeader> rows = new HashMap<>();
		for (PromotionBatchHeader row : promotionBatchRepository.findHeadersByPromotionId(promotion.getId())) {
			rows.put(row.migrationBatchId(), row);
		}
		List<PromotionBatchEligibilityResponse> details = new ArrayList<>();
		for (BatchHeader batch : migrationBatchRepository.findHeadersByMigrationRunId(runId)) {
			PromotionBatchHeader match = rows.get(batch.id());
			if (match == null) {
				details.add(new PromotionBatchEligibilityResponse(
						batch.id(),
						batch.status(),
						PromotionBatchStatus.NOT_ELIGIBLE,
						0L,
						0L));
			} else {
				details.add(new PromotionBatchEligibilityResponse(
						batch.id(),
						batch.status(),
						match.status(),
						nz(match.eligibleRecordCount()),
						nz(match.skippedRecordCount())));
			}
		}
		return details;
	}

	private static PreparePromotionResponse toResponse(
			MigrationPromotion promotion,
			List<PromotionBatchEligibilityResponse> batches) {
		long eligibleRecords = batches.stream()
				.filter(batch -> batch.promotionStatus() == PromotionBatchStatus.ELIGIBLE)
				.mapToLong(PromotionBatchEligibilityResponse::eligibleRecordCount)
				.sum();
		return toResponse(promotion, batches, eligibleRecords);
	}

	private static PreparePromotionResponse toResponse(
			MigrationPromotion promotion,
			List<PromotionBatchEligibilityResponse> batches,
			long eligibleRecords) {
		return new PreparePromotionResponse(
				promotion.getId(),
				promotion.getMigrationRun().getId(),
				promotion.getStatus(),
				nz(promotion.getTotalBatches()),
				nz(promotion.getEligibleBatches()),
				nz(promotion.getSkippedBatches()),
				nz(promotion.getTotalRecords()),
				eligibleRecords,
				nz(promotion.getSkippedRecords()),
				List.copyOf(batches));
	}

	private static PromotionException conflict(String code, String message) {
		return new PromotionException(HttpStatus.CONFLICT.value(), code, message);
	}

	private static int nz(Integer value) {
		return value == null ? 0 : value;
	}

	private static long nz(Long value) {
		return value == null ? 0L : value;
	}
}
