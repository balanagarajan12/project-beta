package com.etp.migration.connector.source.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.etp.migration.processing.BatchProcessingException;
import com.etp.migration.processing.PipelineTiming;
import com.etp.migration.processing.SourceRecord;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CsvPositionedFileTest {

	@TempDir
	Path tempDir;

	@AfterEach
	void releaseTiming() {
		PipelineTiming.bind(null);
	}

	@Test
	void positionedReadsMatchAFullParseIncludingQuotesEmptyLinesAndCrlf() throws Exception {
		String csv = "id,note\r\n"
				+ "1,plain\r\n"
				+ "2,\"has, comma\"\r\n"
				+ "\r\n"
				+ "4,\"line\r\nbreak\"\r\n"
				+ "5,end\r\n";
		Path file = tempDir.resolve("rows.csv");
		Files.writeString(file, csv, StandardCharsets.UTF_8);
		List<SourceRecord> expected;
		try (var stream = CsvRecordReader.all(
				Files.newInputStream(file), StandardCharsets.UTF_8, ',', '"', true)) {
			expected = stream.toList();
		}

		CsvPositionedFile positioned = CsvPositionedFile.open(
				file, tempDir.resolve("rows.idx"), StandardCharsets.UTF_8, ',', '"', true);
		assertThat(positioned.recordCount()).isEqualTo(expected.size());

		PipelineTiming timing = new PipelineTiming();
		PipelineTiming.bind(timing);
		List<SourceRecord> selected;
		long[] numbers = expected.stream().mapToLong(SourceRecord::recordNumber).toArray();
		try (var stream = positioned.readRecordNumbers(numbers)) {
			selected = stream.toList();
		}

		assertThat(selected).extracting(SourceRecord::recordNumber)
				.containsExactlyElementsOf(expected.stream().map(SourceRecord::recordNumber).toList());
		for (int i = 0; i < expected.size(); i++) {
			assertThat(selected.get(i).fields()).isEqualTo(expected.get(i).fields());
		}
		assertThat(timing.counter("sourceRowsParsed")).isEqualTo(expected.size());
	}

	@Test
	void aLateBatchParsesOnlyTheRowsItNames() throws Exception {
		Path file = tempDir.resolve("wide.csv");
		StringBuilder csv = new StringBuilder("id,city\n");
		for (int row = 1; row <= 2_000; row++) {
			csv.append(row).append(",city-").append(row).append('\n');
		}
		Files.writeString(file, csv.toString(), StandardCharsets.UTF_8);
		CsvPositionedFile positioned = CsvPositionedFile.open(
				file, tempDir.resolve("wide.idx"), StandardCharsets.UTF_8, ',', '"', true);

		PipelineTiming timing = new PipelineTiming();
		PipelineTiming.bind(timing);
		List<SourceRecord> selected;
		try (var stream = positioned.readRecordNumbers(new long[] {1_998L, 2_000L, 1_998L})) {
			selected = stream.toList();
		}

		assertThat(selected).extracting(SourceRecord::recordNumber).containsExactly(1_998L, 2_000L);
		assertThat(selected.get(0).fields()).containsEntry("id", "1998");
		assertThat(selected.get(1).fields()).containsEntry("city", "city-2000");
		assertThat(timing.counter("sourceRowsParsed")).isEqualTo(2L);
		assertThat(timing.counter("sourceBytesRead")).isPositive().isLessThan(Files.size(file));
	}

	@Test
	void indexReportsBytesAsTheyAreRead() throws Exception {
		StringBuilder csv = new StringBuilder("id\n");
		for (int row = 1; row <= 40; row++) {
			csv.append(row).append('\n');
		}
		Path file = tempDir.resolve("progress.csv");
		Files.writeString(file, csv.toString(), StandardCharsets.UTF_8);
		List<Long> reports = new ArrayList<>();

		CsvPositionedFile positioned = CsvPositionedFile.open(
				file,
				tempDir.resolve("progress.idx"),
				StandardCharsets.UTF_8,
				',',
				'"',
				true,
				(stage, bytes, rows) -> {
					assertThat(stage).isEqualTo(SourceByteProgress.Stage.INDEX);
					assertThat(bytes).isPositive();
					assertThat(rows).isNotNegative();
					reports.add(bytes);
				},
				16,
				System.nanoTime() + Duration.ofMinutes(1).toNanos());

		assertThat(positioned.recordCount()).isEqualTo(40);
		assertThat(reports).isNotEmpty().isSorted();
	}

	@Test
	void sourceCountTimesOutWhenTheDeadlineHasPassed() throws Exception {
		Path file = tempDir.resolve("expired.csv");
		Files.writeString(file, "id\n1\n", StandardCharsets.UTF_8);

		assertThatThrownBy(() -> CsvPositionedFile.open(
				file,
				tempDir.resolve("expired.idx"),
				StandardCharsets.UTF_8,
				',',
				'"',
				true,
				null,
				16,
				System.nanoTime()))
				.isInstanceOf(BatchProcessingException.class)
				.hasRootCauseMessage("Source count timed out");
		assertThat(Files.exists(tempDir.resolve("expired.idx"))).isFalse();
	}
}
