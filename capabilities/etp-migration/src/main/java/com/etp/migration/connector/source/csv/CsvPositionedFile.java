package com.etp.migration.connector.source.csv;

import com.etp.migration.processing.BatchProcessingException;
import com.etp.migration.processing.MigrationErrorCodes;
import com.etp.migration.processing.PipelineTiming;
import com.etp.migration.processing.SourceRecord;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local CSV plus a record-start index. A batch seeks to the source rows it names instead of scanning
 * every preceding row. The index is one 8-byte offset per data row and lives on disk.
 * Delimiter and quote must be single ASCII bytes, which is the CSV configuration this pipeline accepts.
 */
public final class CsvPositionedFile {

	private static final Logger log = LoggerFactory.getLogger(CsvPositionedFile.class);
	private static final int MAGIC = 0x43524F31;
	private static final int HEADER_BYTES = 16;
	private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
	/** Bytes read between index progress reports. A report is not emitted until this many new bytes arrive. */
	public static final int PROGRESS_BYTES = 8 * 1024 * 1024;
	private static final int READ_BUFFER_BYTES = 1024 * 1024;
	/** Bounds the index scan. Checked between reads so a scan cannot run without a deadline. */
	private static final long INDEX_LIMIT_NANOS = Duration.ofMinutes(30).toNanos();

	private final Path csv;
	private final Path index;
	private final Charset encoding;
	private final char delimiter;
	private final char quote;
	private final boolean hasHeader;
	private final List<String> headers;
	private final int recordCount;
	private final long fileSize;

	private CsvPositionedFile(
			Path csv,
			Path index,
			Charset encoding,
			char delimiter,
			char quote,
			boolean hasHeader,
			List<String> headers,
			int recordCount,
			long fileSize) {
		this.csv = csv;
		this.index = index;
		this.encoding = encoding;
		this.delimiter = delimiter;
		this.quote = quote;
		this.hasHeader = hasHeader;
		this.headers = headers;
		this.recordCount = recordCount;
		this.fileSize = fileSize;
	}

	public static boolean positionable(char delimiter, char quote) {
		return delimiter < 128 && quote < 128 && delimiter != quote && delimiter != '\r' && delimiter != '\n'
				&& quote != '\r' && quote != '\n';
	}

	public static CsvPositionedFile open(
			Path csv,
			Path index,
			Charset encoding,
			char delimiter,
			char quote,
			boolean hasHeader) {
		return open(csv, index, encoding, delimiter, quote, hasHeader, null);
	}

	public static CsvPositionedFile open(
			Path csv,
			Path index,
			Charset encoding,
			char delimiter,
			char quote,
			boolean hasHeader,
			SourceByteProgress progress) {
		return open(
				csv,
				index,
				encoding,
				delimiter,
				quote,
				hasHeader,
				progress,
				PROGRESS_BYTES,
				System.nanoTime() + INDEX_LIMIT_NANOS);
	}

	static CsvPositionedFile open(
			Path csv,
			Path index,
			Charset encoding,
			char delimiter,
			char quote,
			boolean hasHeader,
			SourceByteProgress progress,
			int progressBytes,
			long deadlineNanos) {
		if (!positionable(delimiter, quote)) {
			throw new IllegalArgumentException("CSV positioning requires a single-byte delimiter and quote");
		}
		try {
			long size = Files.size(csv);
			if (indexMatches(index, size)) {
				try {
					return load(csv, index, encoding, delimiter, quote, hasHeader, size);
				} catch (IOException ignored) {
					// The index header matched and the body did not. Rebuild it.
				}
			}
			writeIndex(csv, index, encoding, delimiter, quote, hasHeader, size, progress, progressBytes, deadlineNanos);
			return load(csv, index, encoding, delimiter, quote, hasHeader, size);
		} catch (IOException ex) {
			throw new BatchProcessingException(
					MigrationErrorCodes.SOURCE_READ_ERROR,
					"Unable to read source batch",
					ex);
		}
	}

	public int recordCount() {
		return recordCount;
	}

	public long fileSize() {
		return fileSize;
	}

	public Stream<SourceRecord> readAll() {
		try {
			InputStream input = Files.newInputStream(csv);
			return CsvRecordReader.all(input, encoding, delimiter, quote, hasHeader);
		} catch (IOException ex) {
			throw new BatchProcessingException(
					MigrationErrorCodes.SOURCE_READ_ERROR,
					"Unable to read source batch",
					ex);
		}
	}

	public Stream<SourceRecord> readRecordNumbers(long[] sourceRecordNumbers) {
		long[][] ranges = runs(sourceRecordNumbers, recordCount);
		if (ranges.length == 0) {
			return Stream.empty();
		}
		RunCursor cursor = new RunCursor(ranges);
		return StreamSupport.stream(
						Spliterators.spliteratorUnknownSize(cursor, Spliterator.ORDERED),
						false)
				.onClose(cursor::close);
	}

	private Stream<SourceRecord> readRun(long start, long end) {
		long offset = offset(start);
		long next = end == recordCount ? fileSize : offset(end + 1);
		PipelineTiming.add("sourceBytesRead", Math.max(0L, next - offset));
		PipelineTiming.increment("positionedSourceReads");
		FileChannel channel = null;
		try {
			channel = FileChannel.open(csv, StandardOpenOption.READ);
			channel.position(offset);
			InputStream input = Channels.newInputStream(channel);
			int count = Math.toIntExact(end - start + 1);
			List<String> names = hasHeader ? headers : null;
			return CsvRecordReader.slice(input, encoding, delimiter, quote, names, start, count);
		} catch (RuntimeException | IOException ex) {
			if (channel != null) {
				try {
					channel.close();
				} catch (IOException ignored) {
					// The original failure is the one the caller sees.
				}
			}
			if (ex instanceof BatchProcessingException batch) {
				throw batch;
			}
			throw new BatchProcessingException(
					MigrationErrorCodes.SOURCE_READ_ERROR,
					"Unable to read source batch",
					ex);
		}
	}

	private final class RunCursor implements Iterator<SourceRecord> {
		private final long[][] ranges;
		private int run;
		private Stream<SourceRecord> open;
		private Iterator<SourceRecord> current = java.util.Collections.emptyIterator();

		private RunCursor(long[][] ranges) {
			this.ranges = ranges;
		}

		@Override
		public boolean hasNext() {
			while (!current.hasNext()) {
				closeOpen();
				if (run >= ranges.length) {
					return false;
				}
				long start = ranges[run][0];
				long end = ranges[run][1];
				run++;
				open = readRun(start, end);
				current = open.iterator();
			}
			return true;
		}

		@Override
		public SourceRecord next() {
			if (!hasNext()) {
				throw new NoSuchElementException();
			}
			return current.next();
		}

		private void close() {
			closeOpen();
		}

		private void closeOpen() {
			if (open != null) {
				open.close();
				open = null;
				current = java.util.Collections.emptyIterator();
			}
		}
	}

	private long offset(long recordNumber) {
		if (recordNumber < 1 || recordNumber > recordCount) {
			throw new BatchProcessingException(
					MigrationErrorCodes.SOURCE_READ_ERROR,
					"Source does not contain record " + recordNumber);
		}
		try (var file = new java.io.RandomAccessFile(index.toFile(), "r")) {
			file.seek(HEADER_BYTES + (recordNumber - 1) * 8L);
			return file.readLong();
		} catch (IOException ex) {
			throw new BatchProcessingException(
					MigrationErrorCodes.SOURCE_READ_ERROR,
					"Unable to read source batch",
					ex);
		}
	}

	static boolean indexMatches(Path index, long csvSize) {
		if (!Files.isRegularFile(index)) {
			return false;
		}
		try (var file = new java.io.RandomAccessFile(index.toFile(), "r")) {
			if (file.length() < HEADER_BYTES || file.readInt() != MAGIC) {
				return false;
			}
			return file.readLong() == csvSize;
		} catch (IOException ex) {
			return false;
		}
	}

	private static CsvPositionedFile load(
			Path csv,
			Path index,
			Charset encoding,
			char delimiter,
			char quote,
			boolean hasHeader,
			long fileSize) throws IOException {
		int count;
		try (var file = new java.io.RandomAccessFile(index.toFile(), "r")) {
			if (file.readInt() != MAGIC || file.readLong() != fileSize) {
				throw new IOException("CSV row index does not match the source file");
			}
			count = file.readInt();
			long expected = HEADER_BYTES + count * 8L;
			if (count < 0 || file.length() != expected) {
				throw new IOException("CSV row index is incomplete");
			}
		}
		List<String> headers = List.of();
		if (hasHeader) {
			try (InputStream input = Files.newInputStream(csv)) {
				headers = CsvRecordReader.headerNames(input, encoding, delimiter, quote);
			}
		}
		return new CsvPositionedFile(csv, index, encoding, delimiter, quote, hasHeader, headers, count, fileSize);
	}

	private static void writeIndex(
			Path csv,
			Path index,
			Charset encoding,
			char delimiter,
			char quote,
			boolean hasHeader,
			long fileSize,
			SourceByteProgress progress,
			int progressBytes,
			long deadlineNanos) throws IOException {
		if (index.getParent() != null) {
			Files.createDirectories(index.getParent());
		}
		log.info("Source index started bytes={}", fileSize);
		Path partial = index.resolveSibling(index.getFileName().toString() + ".partial");
		int count;
		try (InputStream input = new BufferedInputStream(Files.newInputStream(csv), READ_BUFFER_BYTES);
				OutputStream out = new BufferedOutputStream(Files.newOutputStream(partial));
				DataOutputStream data = new DataOutputStream(out)) {
			data.writeInt(MAGIC);
			data.writeLong(fileSize);
			data.writeInt(0);
			count = scan(input, delimiter, quote, hasHeader, data, progress, progressBytes, deadlineNanos);
		} catch (IOException | RuntimeException ex) {
			Files.deleteIfExists(partial);
			throw ex;
		}
		try (var file = new java.io.RandomAccessFile(partial.toFile(), "rw")) {
			file.seek(12);
			file.writeInt(count);
		} catch (IOException | RuntimeException ex) {
			Files.deleteIfExists(partial);
			throw ex;
		}
		try {
			Files.move(partial, index, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException atomic) {
			Files.move(partial, index, StandardCopyOption.REPLACE_EXISTING);
		}
		if (hasHeader) {
			try (InputStream input = Files.newInputStream(csv)) {
				CsvRecordReader.headerNames(input, encoding, delimiter, quote);
			}
		}
	}

	private static int scan(
			InputStream input,
			char delimiter,
			char quote,
			boolean hasHeader,
			DataOutputStream offsets,
			SourceByteProgress progress,
			int progressBytes,
			long deadlineNanos) throws IOException {
		PushbackInputStream in = new PushbackInputStream(input, 4);
		long position = skipBom(in);
		boolean inQuote = false;
		boolean sawValue = false;
		boolean headerPending = hasHeader;
		long recordStart = position;
		int count = 0;
		long nextReport = progressBytes;
		int value;
		while (true) {
			if (System.nanoTime() >= deadlineNanos) {
				throw new IOException("Source count timed out");
			}
			value = in.read();
			if (value < 0) {
				break;
			}
			position++;
			if (progress != null && progressBytes > 0 && position >= nextReport) {
				progress.advanced(SourceByteProgress.Stage.INDEX, position, count);
				nextReport = position + progressBytes;
			}
			if (inQuote) {
				if (value == quote) {
					int next = in.read();
					if (next == quote) {
						position++;
						continue;
					}
					if (next >= 0) {
						in.unread(next);
					}
					inQuote = false;
				}
				continue;
			}
			if (value == quote) {
				inQuote = true;
				sawValue = true;
				continue;
			}
			if (value == '\n' || value == '\r') {
				if (value == '\r') {
					int next = in.read();
					if (next == '\n') {
						position++;
					} else if (next >= 0) {
						in.unread(next);
					}
				}
				if (sawValue) {
					if (headerPending) {
						headerPending = false;
					} else {
						offsets.writeLong(recordStart);
						count = increment(count);
					}
				}
				recordStart = position;
				sawValue = false;
				continue;
			}
			sawValue = true;
			if (delimiter == value) {
				continue;
			}
		}
		if (inQuote) {
			throw new IOException("CSV record is missing a closing quote");
		}
		if (sawValue) {
			if (!headerPending) {
				offsets.writeLong(recordStart);
				count = increment(count);
			}
		}
		return count;
	}

	private static int increment(int count) {
		if (count == Integer.MAX_VALUE) {
			throw new BatchProcessingException(
					MigrationErrorCodes.SOURCE_READ_ERROR,
					"Source has too many records to index");
		}
		return count + 1;
	}

	private static long skipBom(PushbackInputStream in) throws IOException {
		byte[] prefix = in.readNBytes(3);
		if (prefix.length == 3 && prefix[0] == UTF8_BOM[0] && prefix[1] == UTF8_BOM[1] && prefix[2] == UTF8_BOM[2]) {
			return 3L;
		}
		for (int i = prefix.length - 1; i >= 0; i--) {
			in.unread(prefix[i] & 0xFF);
		}
		return 0L;
	}

	static long[][] runs(long[] sourceRecordNumbers, int recordCount) {
		if (sourceRecordNumbers == null || sourceRecordNumbers.length == 0) {
			return new long[0][];
		}
		long[] sorted = Arrays.copyOf(sourceRecordNumbers, sourceRecordNumbers.length);
		Arrays.sort(sorted);
		List<long[]> runs = new ArrayList<>();
		long start = -1L;
		long previous = -1L;
		for (long record : sorted) {
			if (record < 1 || record > recordCount) {
				throw new BatchProcessingException(
						MigrationErrorCodes.SOURCE_READ_ERROR,
						"Source does not contain record " + record);
			}
			if (record == previous) {
				continue;
			}
			if (start < 0) {
				start = record;
			} else if (record != previous + 1) {
				runs.add(new long[] {start, previous});
				start = record;
			}
			previous = record;
		}
		if (start > 0) {
			runs.add(new long[] {start, previous});
		}
		return runs.toArray(long[][]::new);
	}
}
