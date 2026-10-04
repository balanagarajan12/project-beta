package com.etp.migration.promotion;

import com.etp.migration.connector.target.postgresql.PostgreSqlPromotionWriter;
import com.etp.migration.connector.target.postgresql.PromotionTargetConfiguration;
import com.etp.migration.connector.target.uam.UamStagingPromotion;
import com.etp.migration.entity.MigrationBatchStatus;
import com.etp.migration.entity.MigrationDefinition;
import com.etp.migration.entity.MigrationPromotion;
import com.etp.migration.entity.MigrationPromotionBatch;
import com.etp.migration.entity.MigrationRun;
import com.etp.migration.entity.MigrationRunStatus;
import com.etp.migration.entity.PromotionBatchStatus;
import com.etp.migration.entity.PromotionStatus;
import com.etp.migration.processing.MigrationErrorCodes;
import com.etp.migration.promotion.dto.ExecutePromotionResponse;
import com.etp.migration.promotion.dto.PromotionBatchExecutionResponse;
import com.etp.migration.repository.MigrationBatchRepository;
import com.etp.migration.repository.MigrationPromotionBatchRepository;
import com.etp.migration.repository.MigrationPromotionRepository;
import com.etp.migration.repository.MigrationReconciliationRepository;
import com.etp.migration.repository.MigrationRunRepository;
import com.etp.migration.run.MigrationRunNotFoundException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PromotionExecutionService {

	private static final String POSTGRESQL_TARGET = "POSTGRESQL";
	private static final EnumSet<MigrationRunStatus> EXECUTION_COMPLETE = EnumSet.of(
			MigrationRunStatus.COMPLETED,
			MigrationRunStatus.COMPLETED_WITH_ERRORS);
	private static final EnumSet<MigrationBatchStatus> ACTIVE_EXECUTION = EnumSet.of(
			MigrationBatchStatus.CLAIMED,
			MigrationBatchStatus.PROCESSING,
			MigrationBatchStatus.RETRY_PENDING);

	private final TransactionTemplate transactionTemplate;
	private final MigrationRunRepository migrationRunRepository;
	private final MigrationBatchRepository migrationBatchRepository;
	private final MigrationReconciliationRepository reconciliationRepository;
	private final MigrationPromotionRepository promotionRepository;
	private final MigrationPromotionBatchRepository promotionBatchRepository;
	private final PostgreSqlPromotionWriter promotionWriter;
	private final UamStagingPromotion uamStagingPromotion;

	public PromotionExecutionService(
			PlatformTransactionManager transactionManager,
			MigrationRunRepository migrationRunRepository,
			MigrationBatchRepository migrationBatchRepository,
			MigrationReconciliationRepository reconciliationRepository,
			MigrationPromotionRepository promotionRepository,
			MigrationPromotionBatchRepository promotionBatchRepository,
			PostgreSqlPromotionWriter promotionWriter,
			UamStagingPromotion uamStagingPromotion) {
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.migrationRunRepository = migrationRunRepository;
		this.migrationBatchRepository = migrationBatchRepository;
		this.reconciliationRepository = reconciliationRepository;
		this.promotionRepository = promotionRepository;
		this.promotionBatchRepository = promotionBatchRepository;
		this.promotionWriter = promotionWriter;
		this.uamStagingPromotion = uamStagingPromotion;
	}

	public ExecutePromotionResponse promote(UUID runId) {
		StartedPromotion started = transactionTemplate.execute(status -> begin(runId));
		if (started.alreadyComplete()) {
			return transactionTemplate.execute(status -> toResponse(started.promotionId()));
		}
		for (EligibleBatch batch : started.eligibleBatches()) {
			promoteBatch(started, batch);
		}
		return transactionTemplate.execute(status -> complete(started.promotionId()));
	}

	private StartedPromotion begin(UUID runId) {
		MigrationRun run = migrationRunRepository.findByIdForUpdate(runId)
				.orElseThrow(() -> new MigrationRunNotFoundException(runId));
		MigrationDefinition definition = run.getMigrationDefinition();
		Map<String, Object> configuration = definition.getConfiguration() == null
				? Map.of()
				: new HashMap<>(definition.getConfiguration());
		requirePostgresqlTarget(definition.getTargetType());
		PromotionTargetConfiguration promotionConfig = PromotionConfigurationGate.requireEnabled(configuration);
		Map<String, Object> mappingConfiguration = mappingConfiguration(definition);
		requireExecutionComplete(run);
		requireNoActiveBatches(runId);
		requireReconciliation(runId);

		List<MigrationPromotion> existing = promotionRepository.findByMigrationRun_IdOrderByCreatedAtDesc(runId);
		if (existing.stream().anyMatch(promotion -> promotion.getStatus() == PromotionStatus.PROMOTING)) {
			throw conflict(
					MigrationErrorCodes.PROMOTION_ALREADY_IN_PROGRESS,
					"A promotion is already in progress for this run");
		}
		MigrationPromotion promoted = existing.stream()
				.filter(promotion -> promotion.getStatus() == PromotionStatus.PROMOTED)
				.findFirst()
				.orElse(null);
		if (promoted != null) {
			return StartedPromotion.completed(promoted.getId(), runId);
		}
		MigrationPromotion ready = existing.stream()
				.filter(promotion -> promotion.getStatus() == PromotionStatus.READY)
				.findFirst()
				.orElse(null);
		if (ready == null) {
			throw conflict(
					MigrationErrorCodes.PROMOTION_NOT_READY,
					"Prepare a promotion before executing promote");
		}

		Instant now = Instant.now();
		ready.setStatus(PromotionStatus.PROMOTING);
		if (ready.getStartedAt() == null) {
			ready.setStartedAt(now);
		}
		ready.setErrorCode(null);
		ready.setErrorMessage(null);
		List<EligibleBatch> eligible = new ArrayList<>();
		for (MigrationPromotionBatch batch : promotionBatchRepository
				.findByPromotion_IdAndStatusOrderByMigrationBatch_BatchNumberAsc(
						ready.getId(), PromotionBatchStatus.ELIGIBLE)) {
			eligible.add(new EligibleBatch(
					batch.getId(),
					batch.getMigrationBatch().getId(),
					nz(batch.getEligibleRecordCount())));
		}
		return new StartedPromotion(
				ready.getId(), runId, configuration, mappingConfiguration, promotionConfig, eligible, false);
	}

	private static Map<String, Object> mappingConfiguration(MigrationDefinition definition) {
		if (definition.getMapping() == null || definition.getMapping().getConfiguration() == null) {
			return Map.of();
		}
		return new HashMap<>(definition.getMapping().getConfiguration());
	}

	private void promoteBatch(StartedPromotion started, EligibleBatch eligible) {
		transactionTemplate.executeWithoutResult(status -> markBatchPromoting(eligible.promotionBatchId()));
		try {
			if (UamStagingPromotion.configured(started.configuration())) {
				uamStagingPromotion.promoteBatch(
						started.runId(),
						eligible.migrationBatchId(),
						started.configuration(),
						started.mappingConfiguration());
			} else {
				promotionWriter.promoteBatch(
						started.configuration(),
						started.promotionConfig(),
						started.runId(),
						eligible.migrationBatchId());
			}
			transactionTemplate.executeWithoutResult(status -> markBatchPromoted(eligible));
		} catch (RuntimeException ex) {
			transactionTemplate.executeWithoutResult(status -> markBatchFailed(eligible, ex));
		}
	}

	private void markBatchPromoting(Long promotionBatchId) {
		MigrationPromotionBatch batch = promotionBatchRepository.findById(promotionBatchId).orElseThrow();
		batch.setStatus(PromotionBatchStatus.PROMOTING);
		batch.setStartedAt(Instant.now());
		batch.setErrorCode(null);
		batch.setErrorMessage(null);
	}

	private void markBatchPromoted(EligibleBatch eligible) {
		MigrationPromotionBatch batch = promotionBatchRepository.findById(eligible.promotionBatchId()).orElseThrow();
		Instant now = Instant.now();
		batch.setStatus(PromotionBatchStatus.PROMOTED);
		batch.setPromotedRecordCount(eligible.eligibleRecordCount());
		batch.setFailedRecordCount(0L);
		batch.setCompletedAt(now);
		batch.setErrorCode(null);
		batch.setErrorMessage(null);
	}

	private void markBatchFailed(EligibleBatch eligible, RuntimeException ex) {
		MigrationPromotionBatch batch = promotionBatchRepository.findById(eligible.promotionBatchId()).orElseThrow();
		Instant now = Instant.now();
		batch.setStatus(PromotionBatchStatus.FAILED);
		batch.setPromotedRecordCount(0L);
		batch.setFailedRecordCount(eligible.eligibleRecordCount());
		batch.setCompletedAt(now);
		batch.setErrorCode(MigrationErrorCodes.PROMOTION_BATCH_FAILED);
		batch.setErrorMessage(truncate(ex.getMessage() == null ? "Promotion batch failed" : ex.getMessage()));
	}

	private ExecutePromotionResponse complete(UUID promotionId) {
		MigrationPromotion promotion = promotionRepository.findById(promotionId).orElseThrow();
		List<MigrationPromotionBatch> batches = promotionBatchRepository.findByPromotion_Id(promotionId);
		int promotedBatches = 0;
		int failedBatches = 0;
		long promotedRecords = 0L;
		long failedRecords = 0L;
		for (MigrationPromotionBatch batch : batches) {
			if (batch.getStatus() == PromotionBatchStatus.PROMOTED) {
				promotedBatches++;
				promotedRecords += nz(batch.getPromotedRecordCount());
			} else if (batch.getStatus() == PromotionBatchStatus.FAILED) {
				failedBatches++;
				failedRecords += nz(batch.getFailedRecordCount());
			}
		}
		PromotionStatus status;
		if (promotedBatches > 0 && failedBatches == 0) {
			status = PromotionStatus.PROMOTED;
		} else if (promotedBatches > 0) {
			status = PromotionStatus.PARTIALLY_PROMOTED;
		} else {
			status = PromotionStatus.PROMOTION_FAILED;
		}
		Instant now = Instant.now();
		promotion.setStatus(status);
		promotion.setPromotedBatches(promotedBatches);
		promotion.setFailedBatches(failedBatches);
		promotion.setPromotedRecords(promotedRecords);
		promotion.setFailedRecords(failedRecords);
		promotion.setCompletedAt(now);
		if (status == PromotionStatus.PROMOTION_FAILED && failedBatches > 0) {
			promotion.setErrorCode(MigrationErrorCodes.PROMOTION_BATCH_FAILED);
			promotion.setErrorMessage("One or more promotion batches failed");
		}
		return toResponse(promotion, batches);
	}

	private ExecutePromotionResponse toResponse(UUID promotionId) {
		MigrationPromotion promotion = promotionRepository.findById(promotionId).orElseThrow();
		return toResponse(promotion, promotionBatchRepository.findByPromotion_Id(promotionId));
	}

	private static ExecutePromotionResponse toResponse(
			MigrationPromotion promotion,
			List<MigrationPromotionBatch> batches) {
		List<PromotionBatchExecutionResponse> details = new ArrayList<>();
		for (MigrationPromotionBatch batch : batches) {
			Long batchId = batch.getMigrationBatch() == null ? null : batch.getMigrationBatch().getId();
			details.add(new PromotionBatchExecutionResponse(
					batchId,
					batch.getStatus(),
					nz(batch.getEligibleRecordCount()),
					nz(batch.getPromotedRecordCount()),
					nz(batch.getFailedRecordCount()),
					batch.getErrorCode(),
					batch.getErrorMessage()));
		}
		return new ExecutePromotionResponse(
				promotion.getId(),
				promotion.getMigrationRun().getId(),
				promotion.getStatus(),
				nz(promotion.getTotalBatches()),
				nz(promotion.getEligibleBatches()),
				nz(promotion.getPromotedBatches()),
				nz(promotion.getFailedBatches()),
				nz(promotion.getSkippedBatches()),
				nz(promotion.getTotalRecords()),
				nz(promotion.getPromotedRecords()),
				nz(promotion.getFailedRecords()),
				nz(promotion.getSkippedRecords()),
				List.copyOf(details));
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

	private static PromotionException conflict(String code, String message) {
		return new PromotionException(HttpStatus.CONFLICT.value(), code, message);
	}

	private static int nz(Integer value) {
		return value == null ? 0 : value;
	}

	private static long nz(Long value) {
		return value == null ? 0L : value;
	}

	private static String truncate(String message) {
		if (message.length() <= 2000) {
			return message;
		}
		return message.substring(0, 2000);
	}

	private record EligibleBatch(Long promotionBatchId, Long migrationBatchId, long eligibleRecordCount) {
	}

	private record StartedPromotion(
			UUID promotionId,
			UUID runId,
			Map<String, Object> configuration,
			Map<String, Object> mappingConfiguration,
			PromotionTargetConfiguration promotionConfig,
			List<EligibleBatch> eligibleBatches,
			boolean alreadyComplete) {

		static StartedPromotion completed(UUID promotionId, UUID runId) {
			return new StartedPromotion(promotionId, runId, Map.of(), Map.of(), null, List.of(), true);
		}
	}
}
