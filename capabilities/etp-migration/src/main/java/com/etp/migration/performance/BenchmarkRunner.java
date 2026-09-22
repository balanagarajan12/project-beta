package com.etp.migration.performance;

import java.nio.file.Path;

/**
 * Explicit CLI for synthetic CSV generation. Not a Spring {@code CommandLineRunner};
 * normal application startup never generates benchmark data.
 *
 * <pre>
 * java -cp ... com.etp.migration.performance.BenchmarkRunner --records 1000000 --output ./data/benchmark/addresses.csv
 * </pre>
 *
 * End-to-end measurement uses {@code mvn test -Pperformance}.
 */
public final class BenchmarkRunner {

	private BenchmarkRunner() {
	}

	public static void main(String[] args) {
		BenchmarkDataGenerator.main(args);
		System.out.println("Benchmark generation only. Run mvn test -Pperformance to measure the pipeline.");
		System.out.println("Output default: " + Path.of("./data/benchmark/addresses.csv").toAbsolutePath());
	}
}
