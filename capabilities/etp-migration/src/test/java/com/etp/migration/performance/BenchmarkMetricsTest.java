package com.etp.migration.performance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BenchmarkMetricsTest {

	@Test
	void recordsPerSecondUsesDuration() {
		assertThat(BenchmarkMetrics.recordsPerSecond(1000, 500)).isEqualTo(2000.0);
		assertThat(BenchmarkMetrics.heapUsedBytes()).isGreaterThan(0);
	}
}
