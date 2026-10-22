package com.etp.migration.performance;

import static org.assertj.core.api.Assertions.assertThat;

import com.etp.migration.connector.source.csv.CsvPositionedFile;
import com.etp.migration.processing.PipelineTiming;
import com.etp.migration.processing.SourceRecord;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LongSummaryStatistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local measurement of record-number reads. This does not open SFTP and does not use the migration database.
 */
@Tag("performance")
class PositionedSourceBenchmarkTest {

	private static final Logger log = LoggerFactory.getLogger(PositionedSourceBenchmarkTest.class);

	@TempDir
	Path tempDir;

	@AfterEach
	void releaseTiming() {
		PipelineTiming.bind(null);
	}

	@Test
	void oneHundredThousandRowsAreReadByBatchWithoutRescanningTheFile() throws Exception {
		measure(100_000, 1_000);
	}

	@Test
	void fiveHundredThousandRowsAreReadByBatchWithoutRescanningTheFile() throws Exception {
		measure(500_000, 1_000);
	}

	private void measure(int rows, int batchSize) throws Exception {
		Path file = tempDir.resolve("rows-" + rows + ".csv");
		try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			writer.write("id,city,street\n");
			for (int row = 1; row <= rows; row++) {
				writer.write("A");
				writer.write(Integer.toString(row));
				writer.write(",city-");
				writer.write(Integer.toString(row % 1000));
				writer.write(",street-");
				writer.write(Integer.toString(row));
				writer.write('\n');
			}
		}
		long fileBytes = Files.size(file);
		CsvPositionedFile positioned = CsvPositionedFile.open(
				file, tempDir.resolve("rows-" + rows + ".idx"), StandardCharsets.UTF_8, ',', '"', true);
		assertThat(positioned.recordCount()).isEqualTo(rows);

		int batches = (rows + batchSize - 1) / batchSize;
		long[] durations = new long[batches];
		long bytes = 0L;
		long parsed = 0L;
		Runtime runtime = Runtime.getRuntime();
		runtime.gc();
		long heapBefore = runtime.totalMemory() - runtime.freeMemory();
		long started = System.nanoTime();
		for (int batch = 0; batch < batches; batch++) {
			long first = (long) batch * batchSize + 1;
			long last = Math.min(rows, first + batchSize - 1);
			long[] numbers = new long[(int) (last - first + 1)];
			for (int i = 0; i < numbers.length; i++) {
				numbers[i] = first + i;
			}
			PipelineTiming timing = new PipelineTiming();
			PipelineTiming.bind(timing);
			long batchStarted = System.nanoTime();
			long count;
			try (var stream = positioned.readRecordNumbers(numbers)) {
				count = stream.map(SourceRecord::recordNumber).count();
			}
			durations[batch] = (System.nanoTime() - batchStarted) / 1_000_000L;
			assertThat(count).isEqualTo(numbers.length);
			bytes += timing.counter("sourceBytesRead");
			parsed += timing.counter("sourceRowsParsed");
		}
		long elapsedMs = Math.max(1L, (System.nanoTime() - started) / 1_000_000L);
		runtime.gc();
		long heapAfter = runtime.totalMemory() - runtime.freeMemory();
		LongSummaryStatistics stats = Arrays.stream(durations).summaryStatistics();
		long p95 = percentile(durations, 95);
		log.info(
				"positioned read rows={} batches={} batchSize={} elapsedMs={} rowsPerSecond={} bytesRead={} rowsParsed={} fileBytes={} avgMs={} p95Ms={} maxMs={} heapBefore={} heapAfter={}",
				rows,
				batches,
				batchSize,
				elapsedMs,
				rows * 1000L / elapsedMs,
				bytes,
				parsed,
				fileBytes,
				stats.getAverage(),
				p95,
				stats.getMax(),
				heapBefore,
				heapAfter);
		assertThat(parsed).isEqualTo(rows);
		assertThat(bytes).isPositive().isLessThan(fileBytes * batches);
	}

	private static long percentile(long[] values, int percentile) {
		long[] sorted = Arrays.copyOf(values, values.length);
		Arrays.sort(sorted);
		int index = Math.min(sorted.length - 1, (int) Math.ceil(percentile / 100.0 * sorted.length) - 1);
		return sorted[Math.max(0, index)];
	}
}
