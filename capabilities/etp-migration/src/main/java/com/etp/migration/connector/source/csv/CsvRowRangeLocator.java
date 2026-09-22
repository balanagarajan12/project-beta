package com.etp.migration.connector.source.csv;

import com.etp.migration.processing.BatchProcessingException;
import com.etp.migration.processing.MigrationErrorCodes;
import java.util.Map;

public record CsvRowRangeLocator(String objectKey, long startRecord, long endRecord) {

	public static CsvRowRangeLocator from(Map<String, Object> locator) {
		if (locator == null || locator.isEmpty()) {
			throw new BatchProcessingException(MigrationErrorCodes.SOURCE_LOCATOR_INVALID, "Batch source locator is required");
		}
		Object type = locator.get("type");
		if (type == null || !"CSV_ROW_RANGE".equalsIgnoreCase(type.toString())) {
			throw new BatchProcessingException(
					MigrationErrorCodes.SOURCE_LOCATOR_INVALID,
					"Batch source locator type is invalid");
		}
		Object objectKey = locator.get("objectKey");
		if (objectKey == null || objectKey.toString().isBlank()) {
			throw new BatchProcessingException(
					MigrationErrorCodes.SOURCE_LOCATOR_INVALID,
					"Batch source locator objectKey is required");
		}
		long start = number(locator.get("startRecord"), "startRecord");
		long end = number(locator.get("endRecord"), "endRecord");
		if (start < 1 || end < start) {
			throw new BatchProcessingException(
					MigrationErrorCodes.SOURCE_LOCATOR_INVALID,
					"Batch source locator record range is invalid");
		}
		return new CsvRowRangeLocator(objectKey.toString(), start, end);
	}

	private static long number(Object value, String field) {
		if (value instanceof Number number) {
			return number.longValue();
		}
		if (value == null) {
			throw new BatchProcessingException(
					MigrationErrorCodes.SOURCE_LOCATOR_INVALID,
					"Batch source locator " + field + " is required");
		}
		try {
			return Long.parseLong(value.toString());
		} catch (NumberFormatException ex) {
			throw new BatchProcessingException(
					MigrationErrorCodes.SOURCE_LOCATOR_INVALID,
					"Batch source locator " + field + " is invalid");
		}
	}
}
