package com.etp.migration.execution;

import com.etp.migration.batch.BatchCompletionCounters;
import java.util.List;

public record EntityBatchOutcome(BatchCompletionCounters counters, List<EntityItemFailure> failures) {

	public EntityBatchOutcome {
		failures = failures == null ? List.of() : List.copyOf(failures);
	}
}
