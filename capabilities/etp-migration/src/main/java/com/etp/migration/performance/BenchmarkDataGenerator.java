package com.etp.migration.performance;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/**
 * Streams deterministic address-like CSV rows to disk. Does not retain the dataset in memory.
 * Not invoked during normal application startup.
 */
public final class BenchmarkDataGenerator {

	public static final String HEADER =
			"record_id,house_number,street_name,street_suffix,city,state,postal_code,country,address_line_1,address_line_2,opened_on";

	private static final List<String> STREETS = List.of(
			"Main", "Oak", "Maple", "Cedar", "Pine", "Elm", "Walnut", "Cherry", "Willow", "Ash");
	private static final List<String> SUFFIXES = List.of("St", "Ave", "Rd", "Blvd", "Ln");
	private static final List<String> CITIES = List.of(
			"Salem", "Knoxville", "Nashville", "Memphis", "Chattanooga", "Franklin", "Murfreesboro");
	private static final List<String> STATES = List.of("TN", "AL", "GA", "KY", "NC");

	private BenchmarkDataGenerator() {
	}

	public static long generate(BenchmarkConfiguration configuration) {
		Path output = configuration.outputFile();
		try {
			Files.createDirectories(output.getParent() == null ? Path.of(".") : output.getParent());
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		long written = 0L;
		try (BufferedWriter writer = new BufferedWriter(Files.newBufferedWriter(output, StandardCharsets.UTF_8), 1 << 20)) {
			writer.write(HEADER);
			writer.write('\n');
			written += HEADER.length() + 1L;
			for (int i = 1; i <= configuration.recordCount(); i++) {
				String row = row(i);
				writer.write(row);
				writer.write('\n');
				written += row.length() + 1L;
			}
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return written;
	}

	public static void main(String[] args) {
		int records = 1_000_000;
		Path output = Path.of("./data/benchmark/addresses.csv");
		String layout = "flat";
		for (int i = 0; i < args.length; i++) {
			if ("--records".equals(args[i]) && i + 1 < args.length) {
				records = Integer.parseInt(args[++i]);
			} else if ("--output".equals(args[i]) && i + 1 < args.length) {
				output = Path.of(args[++i]);
			} else if ("--layout".equals(args[i]) && i + 1 < args.length) {
				layout = args[++i];
			}
		}
		if ("single".equalsIgnoreCase(layout)) {
			EntityDatasetGenerator.Shape shape = EntityDatasetGenerator.writeSingle(output, records);
			System.out.println("Wrote single-file entity dataset leaves=" + records
					+ " entities=" + shape.entities() + " to " + output.toAbsolutePath());
			return;
		}
		if ("multi".equalsIgnoreCase(layout)) {
			EntityDatasetGenerator.Shape shape = EntityDatasetGenerator.writeMulti(output, records);
			System.out.println("Wrote multi-file entity dataset leaves=" + records
					+ " entities=" + shape.entities() + " to " + output.toAbsolutePath());
			return;
		}
		long bytes = BenchmarkDataGenerator.generate(BenchmarkConfiguration.of(records, BenchmarkConfiguration.DEFAULT_BATCH_SIZE, output));
		System.out.println("Wrote " + records + " flat records (" + bytes + " bytes) to " + output.toAbsolutePath());
	}

	static String row(int recordId) {
		String street = STREETS.get(recordId % STREETS.size());
		String suffix = SUFFIXES.get(recordId % SUFFIXES.size());
		String city = CITIES.get((recordId * 7) % CITIES.size());
		String state = STATES.get((recordId * 3) % STATES.size());
		int house = 1 + (recordId % 9998);
		String postal = String.format("%05d", recordId % 100000);
		String line1 = house + " " + street + " " + suffix;
		String line2 = recordId % 11 == 0 ? "Suite " + (recordId % 50) : "";
		LocalDate opened = LocalDate.of(2020, 1, 1).plusDays(recordId % 365);
		return recordId + "," + house + "," + street + "," + suffix + "," + city + "," + state + ","
				+ postal + ",US," + line1 + "," + line2 + "," + opened;
	}
}
