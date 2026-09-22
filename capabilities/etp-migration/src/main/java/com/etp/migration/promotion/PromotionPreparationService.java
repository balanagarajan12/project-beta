package com.etp.migration.promotion;

import com.etp.migration.connector.target.postgresql.PromotionTargetConfiguration;
import com.etp.migration.entity.MigrationBatch;
import com.etp.migration.entity.MigrationBatchStatus;
import com.etp.migration.entity.MigrationDefinition;
import com.etp.migration.entity.MigrationPromotion;
import com.etp.migration.entity.MigrationPromotionBatch;
import com.etp.migration.entity.MigrationRun;
import com.etp.migration.entity.MigrationRunStatus;
import com.etp.migration.entity.PromotionBatchStatus;
import com.etp.migration.entity.PromotionStatus;
import com.etp.migration.migration.ControlPlaneSupport;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
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
	private static final EnumSet<PromotionStatus> ACTIVE_PROMOTION = EnumSet.of(
			PromotionStatus.NOT_STARTED,
			PromotionStatus.READY,
			PromotionStatus.PROMOTING);

	private final MigrationRunRepository migrationRunRepository;
	private final MigrationBatchRepository migrationBatchRepository;
	private final MigrationReconciliationRepository reconciliationRepository;
	private final MigrationPromotionRepository promotionRepository;
	private final MigrationPromotionBatchRepository promotionBatchRepository;
	private final StagingRecordCounter stagingRecordCounter;

	public PromotionPreparationService(
			MigrationRunRepository migrationRunRepository,
			MigrationBatchRepository migrationBatchRepository,
			MigrationReconciliationRepository reconciliationRepository,
			MigrationPromotionRepository promotionRepository,
			MigrationPromotionBatchRepository promotionBatchRepository,
			StagingRecordCounter stagingRecordCounter) {
		this.migrationRunRepository = migrationRunRepository;
		this.migrationBatchRepository = migrationBatchRepository;
		this.reconciliationRepository = reconciliationRepository;
		this.promotionRepository = promotionRepository;
		this.promotionBatchRepository = promotionBatchRepository;
		this.stagingRecordCounter = stagingRecordCounter;
	}

	@Transactional
	public PreparePromotionResponse prepare(UUID runId, PreparePromotionRequest request) {
		MigrationRun run = migrationRunRepository.findByIdForUpdate(runId)
				.orElseThrow(() -> new MigrationRunNotFoundException(runId));
		MigrationDefinition definition = run.getMigrationDefinition();
		Map<String, Object> configuration = definition.getConfiguration() == null
				? Map.of()
				: definition.getConfiguration();

		requirePostgresqlTarget(definition.getTargetType());
		PromotionTargetConfiguration promotionConfig = requireEnabledPromotion(configuration);
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
				.filter(promotion -> ACTIVE_PROMOTION.contains(promotion.getStatus()))
				.findFirst()
				.orElse(null);
		if (active != null) {
			return toResponse(active, loadEligibility(active, runId));
		}

		Set<Long> alreadyPromotedBatchIds = alreadyPromotedBatchIds(runId);
		List<MigrationBatch> batches = migrationBatchRepository.findByMigrationRun_IdOrderByBatchNumberAsc(runId);
		Instant now = Instant.now();
		MigrationPromotion promotion = new MigrationPromotion();
		promotion.setMigrationRun(run);
		promotion.setStatus(PromotionStatus.READY);
		promotion.setRequestedBy(requestedBy(request));
		promotion.setRequestedAt(now);
		promotion.setPromotedBatches(0);
		promotion.setFailedBatches(0);
		promotion.setPromotedRecords(0L);
		promotion.setFailedRecords(0L);
		promotionRepository.save(promotion);

		List<PromotionBatchEligibilityResponse> details = new ArrayList<>();
		int eligibleBatches = 0;
		int skippedBatches = 0;
		long eligibleRecords = 0L;
		long skippedRecords = 0L;
		for (MigrationBatch batch : batches) {
			long staged = stagingRecordCounter.countStagedRecords(runId, batch.getId(), configuration);
			boolean alreadyPromoted = alreadyPromotedBatchIds.contains(batch.getId());
			boolean executionEligible = EXECUTION_ELIGIBLE.contains(batch.getStatus());
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
			if (!alreadyPromoted) {
				MigrationPromotionBatch promotionBatch = new MigrationPromotionBatch();
				promotionBatch.setPromotion(promotion);
				promotionBatch.setMigrationRun(run);
				promotionBatch.setMigrationBatch(batch);
				promotionBatch.setStatus(promotionStatus);
				promotionBatch.setEligibleRecordCount(eligibleRecordCount);
				promotionBatch.setSkippedRecordCount(skippedRecordCount);
				promotionBatch.setPromotedRecordCount(0L);
				promotionBatch.setFailedRecordCount(0L);
				promotionBatchRepository.save(promotionBatch);
			}
			details.add(new PromotionBatchEligibilityResponse(
					batch.getId(),
					batch.getStatus(),
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

	private static String requestedBy(PreparePromotionRequest request) {
		if (request == null || request.requestedBy() == null || request.requestedBy().isBlank()) {
			return ControlPlaneSupport.SYSTEM_ACTOR;
		}
		return request.requestedBy().trim();
	}

	private static void requirePostgresqlTarget(String targetType) {
		if (targetType == null || !POSTGRESQL_TARGET.equals(targetType.toUpperCase(Locale.ROOT))) {
			throw new PromotionException(
					HttpStatus.BAD_REQUEST.value(),
					MigrationErrorCodes.PROMOTION_UNSUPPORTED_TARGET,
					"Promotion is only supported for PostgreSQL targets");
		}
	}

	private static PromotionTargetConfiguration requireEnabledPromotion(Map<String, Object> configuration) {
		if (configuration == null || !configuration.containsKey("promotion")) {
			throw new PromotionException(
					HttpStatus.BAD_REQUEST.value(),
					MigrationErrorCodes.PROMOTION_NOT_CONFIGURED,
					"Promotion is not configured");
		}
		PromotionTargetConfiguration parsed = PromotionTargetConfiguration.parse(configuration);
		if (!parsed.enabled()) {
			throw new PromotionException(
					HttpStatus.BAD_REQUEST.value(),
					MigrationErrorCodes.PROMOTION_DISABLED,
					"Promotion is disabled");
		}
		return parsed;
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
		Set<Long> ids = new HashSet<>();
		for (MigrationPromotionBatch row : promotionBatchRepository.findByMigrationRun_Id(runId)) {
			if (row.getStatus() == PromotionBatchStatus.PROMOTED && row.getMigrationBatch() != null) {
				ids.add(row.getMigrationBatch().getId());
			}
		}
		return ids;
	}

	private List<PromotionBatchEligibilityResponse> loadEligibility(MigrationPromotion promotion, UUID runId) {
		List<MigrationBatch> batches = migrationBatchRepository.findByMigrationRun_IdOrderByBatchNumberAsc(runId);
		List<MigrationPromotionBatch> rows = promotionBatchRepository.findByPromotion_Id(promotion.getId());
		List<PromotionBatchEligibilityResponse> details = new ArrayList<>();
		for (MigrationBatch batch : batches) {
			MigrationPromotionBatch match = rows.stream()
					.filter(row -> row.getMigrationBatch() != null && row.getMigrationBatch().getId().equals(batch.getId()))
					.findFirst()
					.orElse(null);
			if (match == null) {
				details.add(new PromotionBatchEligibilityResponse(
						batch.getId(),
						batch.getStatus(),
						PromotionBatchStatus.NOT_ELIGIBLE,
						0L,
						0L));
			} else {
				details.add(new PromotionBatchEligibilityResponse(
						batch.getId(),
						batch.getStatus(),
						match.getStatus(),
						nz(match.getEligibleRecordCount()),
						nz(match.getSkippedRecordCount())));
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
