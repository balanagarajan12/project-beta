package com.etp.migration.performance;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record BenchmarkResult(
		String name,
		int recordCount,
		int batchSize,
		int workers,
		int batchCount,
		long durationMs,
		double recordsPerSecond,
		long successCount,
		long failureCount,
		Long targetCount,
		long reconciliationMs,
		long checksumMs,
		long copyMs,
		long peakHeapBytes,
		long heapAfterBytes,
		String status,
		Instant startedAt,
		Instant endedAt,
		Map<String, Object> extras
) {
	public Map<String, Object> toMap() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("name", name);
		map.put("recordCount", recordCount);
		map.put("batchSize", batchSize);
		map.put("workers", workers);
		map.put("batchCount", batchCount);
		map.put("durationMs", durationMs);
		map.put("durationSeconds", durationMs / 1000.0);
		map.put("recordsPerSecond", recordsPerSecond);
		map.put("successCount", successCount);
		map.put("failureCount", failureCount);
		map.put("targetCount", targetCount);
		map.put("reconciliationMs", reconciliationMs);
		map.put("checksumMs", checksumMs);
		map.put("copyMs", copyMs);
		map.put("peakHeapBytes", peakHeapBytes);
		map.put("heapAfterBytes", heapAfterBytes);
		map.put("status", status);
		map.put("startedAt", startedAt);
		map.put("endedAt", endedAt);
		if (extras != null && !extras.isEmpty()) {
			map.put("extras", extras);
		}
		return map;
	}
}
