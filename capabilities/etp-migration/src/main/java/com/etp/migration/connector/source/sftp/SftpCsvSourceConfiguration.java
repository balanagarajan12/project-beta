package com.etp.migration.connector.source.sftp;

import com.etp.migration.connector.source.InvalidSourceConfigurationException;
import com.etp.migration.connector.source.UnsupportedFileFormatException;
import com.etp.migration.entity.ArtifactFileFormat;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public record SftpCsvSourceConfiguration(
		String host,
		int port,
		String credentialRef,
		String remotePath,
		ArtifactFileFormat fileFormat,
		char delimiter,
		boolean hasHeader,
		Charset encoding,
		char quoteCharacter
) {

	private static final Set<String> SECRET_KEYS = Set.of(
			"password",
			"privateKey",
			"private_key",
			"passphrase",
			"secret");

	public static boolean declaresSftp(Map<String, Object> configuration) {
		if (configuration == null) {
			return false;
		}
		Object provider = configuration.get("provider");
		return provider != null && "SFTP".equalsIgnoreCase(provider.toString().trim());
	}

	public static SftpCsvSourceConfiguration from(Map<String, Object> configuration) {
		if (configuration == null || configuration.isEmpty()) {
			throw new InvalidSourceConfigurationException("Source configuration is required");
		}
		if (!declaresSftp(configuration)) {
			throw new InvalidSourceConfigurationException("Source configuration requires provider SFTP");
		}
		rejectSecrets(configuration);
		Object layoutType = configuration.get("layoutType");
		if (layoutType != null && "MULTI_FILE".equalsIgnoreCase(layoutType.toString().trim())) {
			throw new InvalidSourceConfigurationException("SFTP source supports a single remote CSV file");
		}
		String host = required(configuration, "host");
		int port = port(configuration.get("port"));
		String credentialRef = required(configuration, "credentialRef");
		String remotePath = required(configuration, "remotePath");
		ArtifactFileFormat fileFormat = fileFormat(configuration.get("fileFormat"));
		if (fileFormat != ArtifactFileFormat.CSV) {
			throw new UnsupportedFileFormatException("Unsupported file format: " + fileFormat);
		}
		char delimiter = firstChar(text(configuration, "delimiter", ","), "delimiter", ',');
		boolean hasHeader = configuration.containsKey("hasHeader")
				? bool(configuration, "hasHeader", true)
				: bool(configuration, "header", true);
		Charset encoding = charset(text(configuration, "encoding", "UTF-8"));
		char quoteCharacter = firstChar(text(configuration, "quoteCharacter", "\""), "quoteCharacter", '"');
		return new SftpCsvSourceConfiguration(
				host,
				port,
				credentialRef,
				remotePath,
				fileFormat,
				delimiter,
				hasHeader,
				encoding,
				quoteCharacter);
	}

	private static void rejectSecrets(Map<String, Object> configuration) {
		for (String key : SECRET_KEYS) {
			Object value = configuration.get(key);
			if (value != null && !value.toString().isBlank()) {
				throw new InvalidSourceConfigurationException(
						"SFTP credentials must be supplied through credentialRef");
			}
		}
	}

	private static String required(Map<String, Object> configuration, String key) {
		String value = text(configuration, key, null);
		if (value == null || value.isBlank()) {
			throw new InvalidSourceConfigurationException("SFTP source requires " + key);
		}
		return value.trim();
	}

	private static int port(Object value) {
		if (value == null) {
			throw new InvalidSourceConfigurationException("SFTP source requires port");
		}
		int port;
		if (value instanceof Number number) {
			port = number.intValue();
		} else {
			try {
				port = Integer.parseInt(value.toString().trim());
			} catch (NumberFormatException ex) {
				throw new InvalidSourceConfigurationException("SFTP source port is invalid");
			}
		}
		if (port < 1 || port > 65535) {
			throw new InvalidSourceConfigurationException("SFTP source port is invalid");
		}
		return port;
	}

	private static ArtifactFileFormat fileFormat(Object value) {
		if (value == null || value.toString().isBlank()) {
			throw new InvalidSourceConfigurationException("Source configuration requires fileFormat");
		}
		try {
			return ArtifactFileFormat.valueOf(value.toString().trim().toUpperCase(Locale.ROOT));
		} catch (RuntimeException ex) {
			throw new InvalidSourceConfigurationException("Source configuration fileFormat is invalid");
		}
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
}
