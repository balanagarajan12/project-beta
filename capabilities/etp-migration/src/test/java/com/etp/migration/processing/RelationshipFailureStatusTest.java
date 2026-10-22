package com.etp.migration.processing;

import static org.assertj.core.api.Assertions.assertThat;

import com.etp.migration.batch.BatchCompletionCounters;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RelationshipFailureStatusTest {

	@Test
	void assignsUnresolvedRelationshipsToTheBatchThatOwnsTheSourceRecord() {
		Map<Integer, Set<Long>> records = new LinkedHashMap<>();
		records.put(1, new LinkedHashSet<>(List.of(1L)));
		records.put(2, new LinkedHashSet<>(List.of(2L)));

		SourceInspectionService.RelationshipFailurePlacement placement = SourceInspectionService.placeRelationshipFailures(
				records,
				List.of(1L, 1L, 9L));

		assertThat(placement.countsByBatchNumber()).containsExactly(Map.entry(1, 2));
		assertThat(placement.unmatched()).isEqualTo(1);
	}

	@Test
	void preparedRelationshipFailuresIncreaseTheBatchRejectedCount() {
		BatchCompletionCounters counters = MigrationBatchWorker.includePreparedRelationshipFailures(
				new BatchCompletionCounters(3, 3, 0, 0, 0, 0, 0),
				2);

		assertThat(counters.rejectedCount()).isEqualTo(2);
		assertThat(counters.successCount()).isEqualTo(3);
		assertThat(counters.processedCount()).isEqualTo(3);
	}
}
