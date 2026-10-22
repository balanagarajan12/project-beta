package com.etp.migration.connector.source.csv;

import com.etp.migration.connector.source.SourceColumn;
import com.etp.migration.processing.BatchProcessingException;
import com.etp.migration.processing.MigrationErrorCodes;
import com.etp.migration.processing.PipelineTiming;
import com.etp.migration.processing.SourceRecord;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * Streams CSV records from an open input. Callers supply the bytes; this class does not open files or remote stores.
 */
public final class CsvRecordReader {

	public record Scan(List<SourceColumn> columns, long recordCount, String checksum) {
	}

	private static final int BYTE_ORDER_MARK = '\uFEFF';

	private CsvRecordReader() {
	}

	public static CSVFormat format(char delimiter, char quote, boolean hasHeader) {
		CSVFormat.Builder builder = CSVFormat.DEFAULT.builder()
				.setDelimiter(delimiter)
				.setQuote(quote)
				.setIgnoreEmptyLines(true)
				.setTrim(false);
		if (hasHeader) {
			builder.setHeader().setSkipHeaderRecord(true);
		}
		return builder.get();
	}

	public static Scan scan(
			InputStream input,
			Charset encoding,
			char delimiter,
			char quote,
			boolean hasHeader) throws IOException {
		MessageDigest digest = sha256();
		List<SourceColumn> columns = List.of();
		long recordCount = 0L;
		try (DigestInputStream digestStream = new DigestInputStream(input, digest);
				BufferedReader reader = reader(digestStream, encoding);
				CSVParser parser = format(delimiter, quote, hasHeader).parse(reader)) {
			if (hasHeader) {
				columns = toColumns(requireUniqueHeaders(parser.getHeaderNames()));
			}
			Iterator<CSVRecord> records = parser.iterator();
			while (records.hasNext()) {
				CSVRecord record = records.next();
				if (!hasHeader && columns.isEmpty()) {
					columns = syntheticColumns(record.size());
				}
				recordCount++;
			}
		}
		return new Scan(columns, recordCount, HexFormat.of().formatHex(digest.digest()));
	}

	public static Stream<SourceRecord> all(
			InputStream input,
			Charset encoding,
			char delimiter,
			char quote,
			boolean hasHeader) {
		return all(input, encoding, delimiter, quote, hasHeader, null);
	}

	public static Stream<SourceRecord> all(
			InputStream input,
			Charset encoding,
			char delimiter,
			char quote,
			boolean hasHeader,
			Function<RuntimeException, RuntimeException> failure) {
		return open(input, encoding, delimiter, quote, hasHeader, 1L, Long.MAX_VALUE, false, failure);
	}

	public static Stream<SourceRecord> range(
			InputStream input,
			Charset encoding,
			char delimiter,
			char quote,
			boolean hasHeader,
			long startRecord,
			long endRecord) {
		return range(input, encoding, delimiter, quote, hasHeader, startRecord, endRecord, null);
	}

	public static Stream<SourceRecord> range(
			InputStream input,
			Charset encoding,
			char delimiter,
			char quote,
			boolean hasHeader,
			long startRecord,
			long endRecord,
			Function<RuntimeException, RuntimeException> failure) {
		return open(input, encoding, delimiter, quote, hasHeader, startRecord, endRecord, true, failure);
	}

	/**
	 * Reads {@code count} data records starting at a byte offset the caller has already seeked to.
	 * {@code headers} is null for a headerless file. When present, columns are mapped by position and the
	 * header row is not part of this stream.
	 */
	public static Stream<SourceRecord> slice(
			InputStream input,
			Charset encoding,
			char delimiter,
			char quote,
			List<String> headers,
			long startRecord,
			int count) {
		if (startRecord < 1) {
			throw new IllegalArgumentException("startRecord must be greater than 0");
		}
		if (count < 0) {
			throw new IllegalArgumentException("count must be zero or greater");
		}
		if (count == 0) {
			closeQuietly(input);
			return Stream.empty();
		}
		BufferedReader reader = null;
		CSVParser parser = null;
		try {
			reader = reader(input, encoding);
			CSVFormat.Builder builder = CSVFormat.DEFAULT.builder()
					.setDelimiter(delimiter)
					.setQuote(quote)
					.setIgnoreEmptyLines(true)
					.setTrim(false);
			if (headers != null) {
				builder.setHeader(headers.toArray(String[]::new)).setSkipHeaderRecord(false);
			}
			parser = builder.get().parse(reader);
			final CSVParser openParser = parser;
			final BufferedReader openReader = reader;
			final InputStream openInput = input;
			final Iterator<CSVRecord> records = parser.iterator();
			final List<String> names = headers == null ? List.of() : headers;
			final boolean named = headers != null;
			return StreamSupport.stream(
							Spliterators.spliteratorUnknownSize(
									new Iterator<SourceRecord>() {
										int remaining = count;
										long recordNumber = startRecord;

										@Override
										public boolean hasNext() {
											if (remaining <= 0) {
												return false;
											}
											if (!records.hasNext()) {
												throw new BatchProcessingException(
														MigrationErrorCodes.SOURCE_READ_ERROR,
														"Source does not contain record " + recordNumber);
											}
											return true;
										}

										@Override
										public SourceRecord next() {
											long started = PipelineTiming.start();
											try {
												if (!hasNext()) {
													throw new BatchProcessingException(
															MigrationErrorCodes.SOURCE_READ_ERROR,
															"Source does not contain record " + recordNumber);
												}
												CSVRecord csvRecord = records.next();
												remaining--;
												PipelineTiming.add("sourceRowsParsed", 1L);
												return new SourceRecord(
														recordNumber++,
														toFields(csvRecord, names, named));
											} finally {
												PipelineTiming.stop(PipelineTiming.Stage.SOURCE_READ, started);
											}
										}
									},
									Spliterator.ORDERED),
							false)
					.onClose(() -> closeQuietly(openParser, openReader, openInput));
		} catch (RuntimeException ex) {
			closeQuietly(parser, reader, input);
			throw ex;
		} catch (Exception ex) {
			closeQuietly(parser, reader, input);
			throw new BatchProcessingException(
					MigrationErrorCodes.SOURCE_READ_ERROR,
					"Unable to read source batch",
					ex);
		}
	}

	public static List<String> headerNames(
			InputStream input,
			Charset encoding,
			char delimiter,
			char quote) throws IOException {
		try (BufferedReader reader = reader(input, encoding);
				CSVParser parser = format(delimiter, quote, true).parse(reader)) {
			return requireUniqueHeaders(parser.getHeaderNames());
		}
	}

	private static Stream<SourceRecord> open(
			InputStream input,
			Charset encoding,
			char delimiter,
			char quote,
			boolean hasHeader,
			long startRecord,
			long endRecord,
			boolean bounded,
			Function<RuntimeException, RuntimeException> failure) {
		BufferedReader reader = null;
		CSVParser parser = null;
		try {
			reader = reader(input, encoding);
			parser = format(delimiter, quote, hasHeader).parse(reader);
			List<String> headers = hasHeader ? requireUniqueHeaders(parser.getHeaderNames()) : List.of();
			Iterator<CSVRecord> records = parser.iterator();
			if (bounded) {
				long skipped = 0;
				while (skipped < startRecord - 1) {
					if (!records.hasNext()) {
						throw new BatchProcessingException(
								MigrationErrorCodes.SOURCE_READ_ERROR,
								"Source does not contain the requested batch range");
					}
					records.next();
					skipped++;
				}
			}
			final CSVParser openParser = parser;
			final BufferedReader openReader = reader;
			final InputStream openInput = input;
			final Iterator<CSVRecord> openRecords = records;
			return StreamSupport.stream(
							Spliterators.spliteratorUnknownSize(
									new Iterator<SourceRecord>() {
										long remaining = endRecord - startRecord + 1;
										long recordNumber = startRecord;

										@Override
										public boolean hasNext() {
											return (!bounded || remaining > 0) && openRecords.hasNext();
										}

										@Override
										public SourceRecord next() {
											long started = PipelineTiming.start();
											try {
												if (!hasNext()) {
													throw new NoSuchElementException();
												}
												CSVRecord csvRecord = openRecords.next();
												if (bounded) {
													remaining--;
												}
												return new SourceRecord(
														recordNumber++,
														toFields(csvRecord, headers, hasHeader));
											} catch (RuntimeException ex) {
												throw translate(ex, failure);
											} finally {
												PipelineTiming.stop(PipelineTiming.Stage.SOURCE_READ, started);
											}
										}
									},
									Spliterator.ORDERED),
							false)
					.onClose(() -> closeQuietly(openParser, openReader, openInput));
		} catch (BatchProcessingException ex) {
			closeQuietly(parser, reader, input);
			throw translate(ex, failure);
		} catch (RuntimeException ex) {
			closeQuietly(parser, reader, input);
			throw translate(ex, failure);
		} catch (Exception ex) {
			closeQuietly(parser, reader, input);
			throw new BatchProcessingException(
					MigrationErrorCodes.SOURCE_READ_ERROR,
					"Unable to read source batch",
					ex);
		}
	}

	private static RuntimeException translate(RuntimeException failure, Function<RuntimeException, RuntimeException> mapper) {
		if (mapper == null) {
			return failure;
		}
		RuntimeException translated = mapper.apply(failure);
		return translated == null ? failure : translated;
	}

	static Map<String, Object> toFields(CSVRecord csvRecord, List<String> headers, boolean hasHeader) {
		Map<String, Object> fields = new LinkedHashMap<>();
		if (hasHeader && !headers.isEmpty()) {
			for (String header : headers) {
				fields.put(header, csvRecord.isMapped(header) ? csvRecord.get(header) : null);
			}
			return fields;
		}
		for (int i = 0; i < csvRecord.size(); i++) {
			fields.put("column_" + (i + 1), csvRecord.get(i));
		}
		return fields;
	}

	static void closeQuietly(AutoCloseable... resources) {
		for (AutoCloseable resource : resources) {
			if (resource != null) {
				try {
					resource.close();
				} catch (Exception ignored) {
					// close quietly after a read failure
				}
			}
		}
	}

	/**
	 * Decodes the input and drops a leading byte-order mark so it never becomes part of the first header or value.
	 * The checksum is computed on the raw bytes before decoding and still includes the mark.
	 */
	private static BufferedReader reader(InputStream input, Charset encoding) throws IOException {
		BufferedReader reader = new BufferedReader(new InputStreamReader(input, encoding));
		reader.mark(1);
		if (reader.read() != BYTE_ORDER_MARK) {
			reader.reset();
		}
		return reader;
	}

	/**
	 * Header names are compared exactly: case-sensitive and without trimming, matching how mapping rules look up
	 * source fields. A repeated name would make one column unreachable, so it is rejected.
	 */
	private static List<String> requireUniqueHeaders(List<String> names) {
		Set<String> seen = new HashSet<>();
		for (String name : names) {
			if (name != null && !seen.add(name)) {
				throw new CsvHeaderException("CSV header contains duplicate column name '" + abbreviate(name)
						+ "'. Column names must be unique; comparison is case-sensitive and does not trim whitespace");
			}
		}
		return Collections.unmodifiableList(new ArrayList<>(names));
	}

	private static String abbreviate(String name) {
		return name.length() <= 100 ? name : name.substring(0, 100) + "...";
	}

	private static List<SourceColumn> toColumns(List<String> names) {
		List<SourceColumn> columns = new ArrayList<>();
		int ordinal = 1;
		for (String name : names) {
			String columnName = name == null || name.isBlank() ? "column_" + ordinal : name;
			columns.add(new SourceColumn(columnName, "STRING", ordinal, true));
			ordinal++;
		}
		return List.copyOf(columns);
	}

	private static List<SourceColumn> syntheticColumns(int size) {
		List<SourceColumn> columns = new ArrayList<>();
		for (int i = 1; i <= size; i++) {
			columns.add(new SourceColumn("column_" + i, "STRING", i, true));
		}
		return List.copyOf(columns);
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException ex) {
			throw new com.etp.migration.connector.source.SourceInspectionException("SHA-256 is not available", ex);
		}
	}
}
