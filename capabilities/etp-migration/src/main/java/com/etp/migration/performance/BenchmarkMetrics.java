package com.etp.migration.performance;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;

public final class BenchmarkMetrics {

	private BenchmarkMetrics() {
	}

	public static long heapUsedBytes() {
		MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
		return memory.getHeapMemoryUsage().getUsed();
	}

	public static long heapCommittedBytes() {
		return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getCommitted();
	}

	public static double recordsPerSecond(long records, long durationMs) {
		if (durationMs <= 0) {
			return records;
		}
		return records * 1000.0 / durationMs;
	}

	public static double megabytesPerSecond(long bytes, long durationMs) {
		if (durationMs <= 0) {
			return bytes / (1024.0 * 1024.0);
		}
		return (bytes / (1024.0 * 1024.0)) * 1000.0 / durationMs;
	}
}
