package com.etp.migration.promotion;

import com.etp.migration.entity.MigrationPromotion;
import com.etp.migration.entity.MigrationPromotionBatch;
import com.etp.migration.promotion.dto.ExecutePromotionResponse;
import com.etp.migration.promotion.dto.PromotionBatchExecutionResponse;
import com.etp.migration.repository.MigrationPromotionBatchRepository;
import com.etp.migration.repository.MigrationPromotionRepository;
import com.etp.migration.repository.MigrationRunRepository;
import com.etp.migration.run.MigrationRunNotFoundException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PromotionQueryService {

	private final MigrationRunRepository migrationRunRepository;
	private final MigrationPromotionRepository promotionRepository;
	private final MigrationPromotionBatchRepository promotionBatchRepository;

	public PromotionQueryService(
			MigrationRunRepository migrationRunRepository,
			MigrationPromotionRepository promotionRepository,
			MigrationPromotionBatchRepository promotionBatchRepository) {
		this.migrationRunRepository = migrationRunRepository;
		this.promotionRepository = promotionRepository;
		this.promotionBatchRepository = promotionBatchRepository;
	}

	@Transactional(readOnly = true)
	public ExecutePromotionResponse get(UUID runId) {
		if (!migrationRunRepository.existsById(runId)) {
			throw new MigrationRunNotFoundException(runId);
		}
		List<MigrationPromotion> existing = promotionRepository.findByMigrationRun_IdOrderByCreatedAtDesc(runId);
		if (existing.isEmpty()) {
			throw new PromotionNotFoundException(runId);
		}
		MigrationPromotion promotion = existing.getFirst();
		List<MigrationPromotionBatch> batches = new ArrayList<>(promotionBatchRepository.findByPromotion_Id(promotion.getId()));
		batches.sort(Comparator.comparing(batch -> {
			if (batch.getMigrationBatch() == null || batch.getMigrationBatch().getBatchNumber() == null) {
				return Integer.MAX_VALUE;
			}
			return batch.getMigrationBatch().getBatchNumber();
		}));
		return toResponse(promotion, batches);
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

	private static int nz(Integer value) {
		return value == null ? 0 : value;
	}

	private static long nz(Long value) {
		return value == null ? 0L : value;
	}
}
