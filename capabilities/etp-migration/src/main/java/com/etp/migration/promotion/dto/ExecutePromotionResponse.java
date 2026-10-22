package com.etp.migration.promotion.dto;

import com.etp.migration.entity.PromotionStatus;
import com.etp.migration.run.ExecutionControlState;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ExecutePromotionResponse(
		UUID promotionId,
		UUID runId,
		PromotionStatus status,
		int totalBatches,
		int eligibleBatches,
		int promotedBatches,
		int failedBatches,
		int skippedBatches,
		long totalRecords,
		long promotedRecords,
		long failedRecords,
		long skippedRecords,
		long eligibleRecords,
		List<PromotionBatchExecutionResponse> batches,
		Map<String, Long> batchCounts,
		UUID jobId,
		String jobStatus,
		ExecutionControlState controlState,
		Instant controlUpdatedAt,
		String lastControlAction,
		int inFlightBatches,
		String errorCode,
		String errorMessage
) {
	public ExecutePromotionResponse(
			UUID promotionId,
			UUID runId,
			PromotionStatus status,
			int totalBatches,
			int eligibleBatches,
			int promotedBatches,
			int failedBatches,
			int skippedBatches,
			long totalRecords,
			long promotedRecords,
			long failedRecords,
			long skippedRecords,
			List<PromotionBatchExecutionResponse> batches) {
		this(
				promotionId,
				runId,
				status,
				totalBatches,
				eligibleBatches,
				promotedBatches,
				failedBatches,
				skippedBatches,
				totalRecords,
				promotedRecords,
				failedRecords,
				skippedRecords,
				eligibleRecords(batches),
				batches,
				Map.of(),
				null,
				null,
				ExecutionControlState.RUNNING,
				null,
				null,
				0,
				null,
				null);
	}

	public ExecutePromotionResponse(
			UUID promotionId,
			UUID runId,
			PromotionStatus status,
			int totalBatches,
			int eligibleBatches,
			int promotedBatches,
			int failedBatches,
			int skippedBatches,
			long totalRecords,
			long promotedRecords,
			long failedRecords,
			long skippedRecords,
			List<PromotionBatchExecutionResponse> batches,
			String errorCode,
			String errorMessage) {
		this(
				promotionId,
				runId,
				status,
				totalBatches,
				eligibleBatches,
				promotedBatches,
				failedBatches,
				skippedBatches,
				totalRecords,
				promotedRecords,
				failedRecords,
				skippedRecords,
				eligibleRecords(batches),
				batches,
				Map.of(),
				null,
				null,
				ExecutionControlState.RUNNING,
				null,
				null,
				0,
				errorCode,
				errorMessage);
	}

	public ExecutePromotionResponse withJob(UUID jobId, String jobStatus) {
		return new ExecutePromotionResponse(
				promotionId,
				runId,
				status,
				totalBatches,
				eligibleBatches,
				promotedBatches,
				failedBatches,
				skippedBatches,
				totalRecords,
				promotedRecords,
				failedRecords,
				skippedRecords,
				eligibleRecords,
				batches,
				batchCounts,
				jobId,
				jobStatus,
				controlState,
				controlUpdatedAt,
				lastControlAction,
				inFlightBatches,
				errorCode,
				errorMessage);
	}

	private static long eligibleRecords(List<PromotionBatchExecutionResponse> batches) {
		if (batches == null) {
			return 0L;
		}
		long total = 0L;
		for (PromotionBatchExecutionResponse batch : batches) {
			total += batch.eligibleRecordCount();
		}
		return total;
	}
}
