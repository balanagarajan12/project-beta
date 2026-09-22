package com.etp.migration.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.etp.migration.entity.MigrationRun;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecordCountBatchPlannerTest {

	private final RecordCountBatchPlanner planner = new RecordCountBatchPlanner();

	@Test
	void splitsRecordCountIntoBatches() {
		MigrationRun run = new MigrationRun();
		List<BatchPlanner.PlannedBatch> planned = planner.plan(
				run, new BatchPlanner.BatchPlanRequest(25_000, 10_000));

		assertThat(planned).hasSize(3);
		assertThat(planned.get(0).recordCount()).isEqualTo(10_000);
		assertThat(planned.get(1).recordCount()).isEqualTo(10_000);
		assertThat(planned.get(2).recordCount()).isEqualTo(5_000);
		assertThat(planned.get(2).startOffset()).isEqualTo(20_000);
	}

	@Test
	void splitsOddRemainderIntoThreeBatches() {
		List<BatchPlanner.PlannedBatch> planned = planner.plan(
				new MigrationRun(), new BatchPlanner.BatchPlanRequest(25_001, 10_000));
		assertThat(planned).hasSize(3);
		assertThat(planned.get(2).recordCount()).isEqualTo(5_001);
		assertThat(planned.get(2).startOffset()).isEqualTo(20_000);
		assertThat(planned.get(2).endOffset()).isEqualTo(25_000);
	}

	@Test
	void exactMultipleProducesThreeBatches() {
		assertThat(planner.plan(new MigrationRun(), new BatchPlanner.BatchPlanRequest(30_000, 10_000))).hasSize(3);
	}

	@Test
	void onePastExactMultipleProducesFourBatches() {
		List<BatchPlanner.PlannedBatch> planned = planner.plan(
				new MigrationRun(), new BatchPlanner.BatchPlanRequest(30_001, 10_000));
		assertThat(planned).hasSize(4);
		assertThat(planned.get(3).recordCount()).isEqualTo(1);
	}

	@Test
	void zeroRecordsProducesNoBatches() {
		assertThat(planner.plan(new MigrationRun(), new BatchPlanner.BatchPlanRequest(0, 10_000))).isEmpty();
	}
}
