package com.etp.migration.connector.source.csv;

import com.etp.migration.connector.source.InvalidSourceConfigurationException;
import com.etp.migration.connector.source.UnsupportedFileFormatException;
import com.etp.migration.entity.ArtifactFileFormat;
import com.etp.migration.entity.StorageProvider;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

public record CsvFileSourceConfiguration(
		StorageProvider provider,
		String bucket,
		String objectKey,
		ArtifactFileFormat fileFormat,
		char delimiter,
		boolean hasHeader,
		Charset encoding,
		char quoteCharacter
) {

	public static CsvFileSourceConfiguration from(Map<String, Object> configuration) {
		if (configuration == null || configuration.isEmpty()) {
			throw new InvalidSourceConfigurationException("Source configuration is required");
		}
		StorageProvider provider = enumValue(
				configuration,
				"provider",
				StorageProvider.class,
				null);
		if (provider == null) {
			throw new InvalidSourceConfigurationException("Source configuration requires provider");
		}
		String objectKey = text(configuration, "objectKey", null);
		if (objectKey == null || objectKey.isBlank()) {
			objectKey = text(configuration, "file", null);
		}
		if (objectKey == null || objectKey.isBlank()) {
			throw new InvalidSourceConfigurationException("Source configuration requires objectKey");
		}
		ArtifactFileFormat fileFormat = enumValue(
				configuration,
				"fileFormat",
				ArtifactFileFormat.class,
				null);
		if (fileFormat == null) {
			throw new InvalidSourceConfigurationException("Source configuration requires fileFormat");
		}
		if (fileFormat != ArtifactFileFormat.CSV) {
			throw new UnsupportedFileFormatException("Unsupported file format: " + fileFormat);
		}
		if (provider != StorageProvider.FILE_SYSTEM) {
			throw new com.etp.migration.connector.storage.UnsupportedStorageProviderException(provider);
		}
		char delimiter = firstChar(text(configuration, "delimiter", ","), "delimiter", ',');
		boolean hasHeader = configuration.containsKey("hasHeader")
				? bool(configuration, "hasHeader", true)
				: bool(configuration, "header", true);
		Charset encoding = charset(text(configuration, "encoding", "UTF-8"));
		char quoteCharacter = firstChar(text(configuration, "quoteCharacter", "\""), "quoteCharacter", '"');
		return new CsvFileSourceConfiguration(
				provider,
				text(configuration, "bucket", null),
				objectKey.trim(),
				fileFormat,
				delimiter,
				hasHeader,
				encoding,
				quoteCharacter);
	}

	private static String text(Map<String, Object> configuration, String key, String defaultValue) {
		Object value = configuration.get(key);
		if (value == null) {
			return defaultValue;
		}
		String text = value.toString();
		return text.isBlank() ? defaultValue : text;
	}

	private static boolean bool(Map<String, Object> configuration, String key, boolean defaultValue) {
		Object value = configuration.get(key);
		if (value == null) {
			return defaultValue;
		}
		if (value instanceof Boolean bool) {
			return bool;
		}
		return Boolean.parseBoolean(value.toString());
	}

	private static char firstChar(String value, String field, char defaultValue) {
		if (value == null || value.isBlank()) {
			return defaultValue;
		}
		if (value.length() != 1) {
			throw new InvalidSourceConfigurationException("Source configuration " + field + " must be a single character");
		}
		return value.charAt(0);
	}

	private static Charset charset(String encoding) {
		try {
			return encoding == null || encoding.isBlank()
					? StandardCharsets.UTF_8
					: Charset.forName(encoding);
		} catch (RuntimeException ex) {
			throw new InvalidSourceConfigurationException("Source configuration encoding is not supported");
		}
	}

	private static <E extends Enum<E>> E enumValue(
			Map<String, Object> configuration,
			String key,
			Class<E> type,
			E defaultValue) {
		Object value = configuration.get(key);
		if (value == null) {
			return defaultValue;
		}
		try {
			return Enum.valueOf(type, value.toString().trim().toUpperCase(Locale.ROOT));
		} catch (RuntimeException ex) {
			throw new InvalidSourceConfigurationException("Source configuration " + key + " is invalid");
		}
	}
}
