package com.etp.migration.reconciliation;

import com.etp.migration.entity.ReconciliationStatus;
import com.etp.migration.entity.ReconciliationType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded entity reconciliation summary. The map is aggregate detail for
 * {@code migration_reconciliation.details}. It is not a lineage store.
 */
public record EntityReconciliationResult(
		ReconciliationStatus status,
		long sourceCount,
		long processedCount,
		Long successCount,
		long failureCount,
		long differenceCount,
		int retainedObservationCount,
		Map<String, Object> details) {

	public List<ReconciliationComputation> apply(List<ReconciliationComputation> computations) {
		ReconciliationComputation count = find(computations, ReconciliationType.COUNT);
		ReconciliationComputation checksum = find(computations, ReconciliationType.CHECKSUM);
		ReconciliationComputation target = find(computations, ReconciliationType.TARGET);
		ReconciliationComputation full = find(computations, ReconciliationType.FULL);
		ReconciliationComputation overlaidCount = overlay(count, true);
		ReconciliationStatus fullStatus = ReconciliationEngine.aggregate(
				overlaidCount.status(),
				checksum.status(),
				target.status());
		ReconciliationComputation overlaidFull = overlay(full, false).withStatus(fullStatus);
		List<ReconciliationComputation> overlaid = new ArrayList<>();
		for (ReconciliationComputation computation : computations) {
			overlaid.add(switch (computation.type()) {
				case COUNT -> overlaidCount;
				case FULL -> overlaidFull;
				default -> computation;
			});
		}
		return List.copyOf(overlaid);
	}

	private ReconciliationComputation overlay(ReconciliationComputation computation, boolean replaceCounts) {
		Map<String, Object> merged = new LinkedHashMap<>(computation.details());
		merged.put("entityReconciliation", new LinkedHashMap<>(details));
		ReconciliationStatus status = ReconciliationEngine.aggregate(computation.status(), this.status);
		long source = replaceCounts ? sourceCount : computation.sourceCount();
		long processed = replaceCounts ? processedCount : computation.processedCount();
		long success = replaceCounts && successCount != null ? successCount : computation.successCount();
		long failure = replaceCounts ? failureCount : computation.failureCount();
		long difference = Math.max(computation.differenceCount(), differenceCount);
		return new ReconciliationComputation(
				computation.type(),
				status,
				source,
				processed,
				success,
				computation.rejectedCount(),
				computation.duplicateCount(),
				failure,
				difference,
				merged);
	}

	private static ReconciliationComputation find(
			List<ReconciliationComputation> computations,
			ReconciliationType type) {
		for (ReconciliationComputation computation : computations) {
			if (computation.type() == type) {
				return computation;
			}
		}
		throw new IllegalArgumentException("Missing reconciliation computation " + type);
	}
}
