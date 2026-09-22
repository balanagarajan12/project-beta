package com.etp.migration.batch;

import com.etp.migration.entity.MigrationRun;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RecordCountBatchPlanner implements BatchPlanner {

	@Override
	public List<PlannedBatch> plan(MigrationRun run, BatchPlanRequest request) {
		if (request.recordCount() < 0) {
			throw new IllegalArgumentException("recordCount must be >= 0");
		}
		if (request.batchSize() <= 0) {
			throw new IllegalArgumentException("batchSize must be > 0");
		}
		if (request.recordCount() == 0) {
			return List.of();
		}

		List<PlannedBatch> planned = new ArrayList<>();
		long remaining = request.recordCount();
		long offset = 0;
		int batchNumber = 1;
		while (remaining > 0) {
			int count = (int) Math.min(request.batchSize(), remaining);
			long endOffset = offset + count - 1;
			Map<String, Object> locator = new LinkedHashMap<>();
			locator.put("startOffset", offset);
			locator.put("endOffset", endOffset);
			locator.put("recordCount", count);
			planned.add(new PlannedBatch(batchNumber, count, offset, endOffset, locator));
			offset += count;
			remaining -= count;
			batchNumber++;
		}
		return planned;
	}
}
