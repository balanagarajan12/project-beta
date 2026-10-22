package com.etp.migration.run.dto;

import com.etp.migration.run.ExecutionControlState;
import java.time.Instant;
import java.util.UUID;

public record ExecutionControlResponse(
		UUID runId,
		UUID promotionId,
		ExecutionControlState controlState,
		String action,
		Instant controlUpdatedAt,
		UUID resumeJobId) {
}
