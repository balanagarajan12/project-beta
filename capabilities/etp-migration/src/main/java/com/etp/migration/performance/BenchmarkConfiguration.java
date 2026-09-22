package com.etp.migration.performance;

import java.nio.file.Path;

public record BenchmarkConfiguration(
		int recordCount,
		int batchSize,
		int workers,
		Path outputFile,
		long seed
) {
	public static final int DEFAULT_BATCH_SIZE = 10_000;
	public static final long DEFAULT_SEED = 12L;

	public static BenchmarkConfiguration of(int recordCount, int batchSize, Path outputFile) {
		return new BenchmarkConfiguration(recordCount, batchSize, 1, outputFile, DEFAULT_SEED);
	}
}
