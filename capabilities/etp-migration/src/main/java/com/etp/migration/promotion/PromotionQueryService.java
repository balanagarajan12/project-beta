package com.etp.migration.promotion;

import com.etp.migration.entity.MigrationPhaseJob;
import com.etp.migration.entity.MigrationPromotion;
import com.etp.migration.entity.PromotionBatchStatus;
import com.etp.migration.migration.ControlPlaneSupport;
import com.etp.migration.migration.dto.PagedResponse;
import com.etp.migration.processing.MigrationErrorCodes;
import com.etp.migration.processing.MigrationErrorDetails;
import com.etp.migration.promotion.PromotionBatchRow;
import com.etp.migration.promotion.dto.ExecutePromotionResponse;
import com.etp.migration.promotion.dto.PromotionBatchDetailResponse;
import com.etp.migration.repository.MigrationPhaseJobRepository;
import com.etp.migration.repository.MigrationPromotionBatchRepository;
import com.etp.migration.repository.MigrationPromotionBatchRepository.PromotionBatchStatusCount;
import com.etp.migration.repository.MigrationPromotionRepository;
import com.etp.migration.repository.MigrationRunRepository;
import com.etp.migration.run.MigrationPhase;
import com.etp.migration.run.MigrationRunNotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PromotionQueryService {

	private final MigrationRunRepository migrationRunRepository;
	private final MigrationPromotionRepository promotionRepository;
	private final MigrationPromotionBatchRepository promotionBatchRepository;
	private final MigrationPhaseJobRepository phaseJobRepository;

	public PromotionQueryService(
			MigrationRunRepository migrationRunRepository,
			MigrationPromotionRepository promotionRepository,
			MigrationPromotionBatchRepository promotionBatchRepository,
			MigrationPhaseJobRepository phaseJobRepository) {
		this.migrationRunRepository = migrationRunRepository;
		this.promotionRepository = promotionRepository;
		this.promotionBatchRepository = promotionBatchRepository;
		this.phaseJobRepository = phaseJobRepository;
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
		ExecutePromotionResponse response = toSummary(promotion);
		if (phaseJobRepository == null) {
			return response;
		}
		MigrationPhaseJob job = phaseJobRepository
				.findFirstByMigrationRunIdAndPhaseInOrderByCreatedAtDesc(
						runId,
						java.util.List.of(MigrationPhase.PROMOTION_PREPARATION, MigrationPhase.PROMOTION))
				.orElse(null);
		if (job == null) {
			return response;
		}
		return response.withJob(job.getId(), job.getStatus().name());
	}

	@Transactional(readOnly = true)
	public PagedResponse<PromotionBatchDetailResponse> listBatches(
			UUID runId,
			int page,
			int size,
			String status) {
		if (!migrationRunRepository.existsById(runId)) {
			throw new MigrationRunNotFoundException(runId);
		}
		if (promotionRepository.findByMigrationRun_IdOrderByCreatedAtDesc(runId).isEmpty()) {
			throw new PromotionNotFoundException(runId);
		}
		PageRequest request = PageRequest.of(Math.max(page, 0), size < 1 ? ControlPlaneSupport.DEFAULT_PAGE_SIZE : Math.min(size, ControlPlaneSupport.MAX_PAGE_SIZE));
		Page<PromotionBatchRow> result = status == null || status.isBlank()
				? promotionBatchRepository.pageByRun(runId, request)
				: promotionBatchRepository.pageByRunAndStatus(runId, batchStatus(status), request);
		List<PromotionBatchDetailResponse> content = new ArrayList<>();
		for (PromotionBatchRow row : result.getContent()) {
			content.add(new PromotionBatchDetailResponse(
					row.migrationBatchId(),
					row.batchNumber(),
					row.status(),
					nz(row.eligibleRecordCount()),
					nz(row.promotedRecordCount()),
					nz(row.failedRecordCount()),
					nz(row.skippedRecordCount()),
					row.startedAt(),
					row.completedAt(),
					durationMillis(row.startedAt(), row.completedAt()),
					row.errorCode(),
					MigrationErrorDetails.publicMessage(row.errorMessage())));
		}
		return new PagedResponse<>(content, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
	}

	private static PromotionBatchStatus batchStatus(String status) {
		try {
			return PromotionBatchStatus.valueOf(status.trim());
		} catch (IllegalArgumentException ex) {
			throw new PromotionException(HttpStatus.BAD_REQUEST.value(), MigrationErrorCodes.VALIDATION_ERROR, "Unsupported promotion batch status");
		}
	}

	private ExecutePromotionResponse toSummary(MigrationPromotion promotion) {
		Map<String, Long> batchCounts = new LinkedHashMap<>();
		long eligibleRecords = 0L;
		int promotedBatches = 0;
		int failedBatches = 0;
		int eligibleBatches = 0;
		int skippedBatches = 0;
		int inFlightBatches = 0;
		for (PromotionBatchStatusCount count : promotionBatchRepository.summarize(promotion.getId())) {
			batchCounts.put(count.getStatus().name(), count.getBatches());
			eligibleRecords += count.getEligibleRecords();
			if (count.getStatus() == PromotionBatchStatus.PROMOTED) {
				promotedBatches = (int) count.getBatches();
			} else if (count.getStatus() == PromotionBatchStatus.FAILED) {
				failedBatches = (int) count.getBatches();
			} else if (count.getStatus() == PromotionBatchStatus.ELIGIBLE || count.getStatus() == PromotionBatchStatus.PROMOTING) {
				eligibleBatches += (int) count.getBatches();
				if (count.getStatus() == PromotionBatchStatus.PROMOTING) {
					inFlightBatches = (int) count.getBatches();
				}
			} else if (count.getStatus() == PromotionBatchStatus.NOT_ELIGIBLE) {
				skippedBatches = (int) count.getBatches();
			}
		}
		return new ExecutePromotionResponse(
				promotion.getId(),
				promotion.getMigrationRun().getId(),
				promotion.getStatus(),
				nz(promotion.getTotalBatches()),
				eligibleBatches,
				promotedBatches,
				failedBatches,
				skippedBatches,
				nz(promotion.getTotalRecords()),
				nz(promotion.getPromotedRecords()),
				nz(promotion.getFailedRecords()),
				nz(promotion.getSkippedRecords()),
				eligibleRecords,
				List.of(),
				Map.copyOf(batchCounts),
				null,
				null,
				promotion.getControlState(),
				promotion.getControlUpdatedAt(),
				promotion.getLastControlAction(),
				inFlightBatches,
				promotion.getErrorCode(),
				MigrationErrorDetails.publicMessage(promotion.getErrorMessage()));
	}

	private static Long durationMillis(Instant startedAt, Instant completedAt) {
		if (startedAt == null || completedAt == null) {
			return null;
		}
		return Math.max(0L, Duration.between(startedAt, completedAt).toMillis());
	}

	private static int nz(Integer value) {
		return value == null ? 0 : value;
	}

	private static long nz(Long value) {
		return value == null ? 0L : value;
	}
}
