package com.etp.migration.performance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchmarkDataGeneratorTest {

	@TempDir
	Path tempDir;

	@Test
	void streamsDeterministicRowsWithoutHoldingTheDataset() throws Exception {
		Path output = tempDir.resolve("addresses.csv");
		long bytes = BenchmarkDataGenerator.generate(BenchmarkConfiguration.of(1_000, 10_000, output));
		assertThat(Files.exists(output)).isTrue();
		assertThat(bytes).isGreaterThan(1_000);
		assertThat(Files.size(output)).isEqualTo(bytes);
		long lines = Files.lines(output).count();
		assertThat(lines).isEqualTo(1_001);
		assertThat(Files.readString(output)).startsWith(BenchmarkDataGenerator.HEADER);
		Path second = tempDir.resolve("addresses-2.csv");
		BenchmarkDataGenerator.generate(BenchmarkConfiguration.of(1_000, 10_000, second));
		assertThat(Files.readString(output)).isEqualTo(Files.readString(second));
		assertThat(BenchmarkDataGenerator.row(1)).contains("1,");
		assertThat(BenchmarkDataGenerator.row(1)).isEqualTo(BenchmarkDataGenerator.row(1));
	}
}
