package com.etp.migration.connector.source.csv;

import com.etp.migration.connector.source.InvalidSourceConfigurationException;
import com.etp.migration.mapping.EntityDefinition;
import com.etp.migration.mapping.EntityRelationshipConfiguration;
import com.etp.migration.connector.source.csv.SourceLayout.SourceFileBinding;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads {@code layoutType} from {@code migration_source.configuration}.
 * Configurations that omit {@code layoutType} stay on the existing single-object CSV path.
 */
public final class SourceLayoutConfiguration {

	private static final String LAYOUT_TYPE = "layoutType";
	private static final List<String> SHARED_KEYS = List.of(
			"provider",
			"bucket",
			"delimiter",
			"hasHeader",
			"header",
			"encoding",
			"quoteCharacter",
			"fileFormat");

	private SourceLayoutConfiguration() {
	}

	public static Optional<SourceLayout> parseIfDeclared(Map<String, Object> configuration) {
		if (configuration == null || !configuration.containsKey(LAYOUT_TYPE)) {
			return Optional.empty();
		}
		return Optional.of(parse(configuration));
	}

	public static SourceLayout parse(Map<String, Object> configuration) {
		if (configuration == null) {
			throw new InvalidSourceConfigurationException("Source configuration is required");
		}
		Object rawType = configuration.get(LAYOUT_TYPE);
		if (!(rawType instanceof String text) || text.isBlank()) {
			throw new InvalidSourceConfigurationException("layoutType is required");
		}
		String type = text.trim();
		if (SourceLayoutType.SINGLE_FILE.name().equals(type)) {
			return new SourceLayout(
					SourceLayoutType.SINGLE_FILE,
					List.of(new SourceFileBinding(null, csvConfiguration(configuration, null))));
		}
		if (SourceLayoutType.MULTI_FILE.name().equals(type)) {
			return new SourceLayout(SourceLayoutType.MULTI_FILE, files(configuration));
		}
		throw new InvalidSourceConfigurationException(
				"Unsupported layoutType '" + type + "'. Supported values are SINGLE_FILE and MULTI_FILE");
	}

	public static void validateDeclared(Map<String, Object> sourceConfiguration, Map<String, Object> mappingConfiguration) {
		Optional<SourceLayout> layout = parseIfDeclared(sourceConfiguration);
		if (layout.isPresent() && layout.get().type() == SourceLayoutType.MULTI_FILE && mappingConfiguration != null) {
			validateEntities(layout.get(), mappingConfiguration);
		}
	}

	public static void validateEntities(SourceLayout layout, Map<String, Object> mappingConfiguration) {
		if (layout == null || layout.type() != SourceLayoutType.MULTI_FILE) {
			return;
		}
		Set<String> names = new LinkedHashSet<>();
		EntityRelationshipConfiguration.parseIfPresent(mappingConfiguration).ifPresent(model -> {
			for (EntityDefinition entity : model.entities()) {
				names.add(entity.name());
			}
		});
		for (SourceFileBinding file : layout.files()) {
			if (!names.contains(file.entityName())) {
				throw new InvalidSourceConfigurationException(
						"Source file entity '" + file.entityName()
								+ "' is not defined in the migration mapping configuration.");
			}
		}
	}

	private static List<SourceFileBinding> files(Map<String, Object> configuration) {
		Object raw = configuration.get("files");
		if (raw == null) {
			throw new InvalidSourceConfigurationException("Source files are required for MULTI_FILE");
		}
		if (!(raw instanceof Map<?, ?> map)) {
			throw new InvalidSourceConfigurationException("Source files must be an object");
		}
		if (map.isEmpty()) {
			throw new InvalidSourceConfigurationException("Source files are required for MULTI_FILE");
		}
		List<SourceFileBinding> files = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			String entityName = entry.getKey() == null ? "" : entry.getKey().toString().trim();
			if (entityName.isEmpty()) {
				throw new InvalidSourceConfigurationException("Source file entity name is required");
			}
			if (!seen.add(entityName)) {
				throw new InvalidSourceConfigurationException(
						"Duplicate source file for entity '" + entityName + "'");
			}
			if (!(entry.getValue() instanceof Map<?, ?> fileRaw)) {
				throw new InvalidSourceConfigurationException(
						"Source file configuration for entity '" + entityName + "' must be an object");
			}
			Map<String, Object> file = new LinkedHashMap<>();
			fileRaw.forEach((key, value) -> file.put(String.valueOf(key), value));
			if (!hasPath(file)) {
				throw new InvalidSourceConfigurationException(
						"Source file for entity '" + entityName + "' requires file");
			}
			files.add(new SourceFileBinding(entityName, csvConfiguration(configuration, file)));
		}
		return List.copyOf(files);
	}

	private static boolean hasPath(Map<String, Object> file) {
		return text(file.get("file")) != null || text(file.get("objectKey")) != null;
	}

	private static CsvFileSourceConfiguration csvConfiguration(Map<String, Object> root, Map<String, Object> file) {
		Map<String, Object> csv = new LinkedHashMap<>();
		copyShared(root, csv);
		if (file == null) {
			copyPath(root, csv);
		} else {
			copyShared(file, csv);
			copyPath(file, csv);
		}
		if (!csv.containsKey("objectKey")) {
			String fileName = text(csv.get("file"));
			if (fileName != null) {
				csv.put("objectKey", fileName);
			}
		}
		csv.remove("file");
		csv.remove(LAYOUT_TYPE);
		csv.remove("files");
		if (csv.containsKey("header") && !csv.containsKey("hasHeader")) {
			csv.put("hasHeader", csv.get("header"));
		}
		csv.remove("header");
		if (text(csv.get("provider")) == null) {
			csv.put("provider", "FILE_SYSTEM");
		}
		if (text(csv.get("fileFormat")) == null) {
			csv.put("fileFormat", "CSV");
		}
		return CsvFileSourceConfiguration.from(csv);
	}

	private static void copyShared(Map<String, Object> source, Map<String, Object> target) {
		if (source == null) {
			return;
		}
		for (String key : SHARED_KEYS) {
			if (source.containsKey(key)) {
				target.put(key, source.get(key));
			}
		}
	}

	private static void copyPath(Map<String, Object> source, Map<String, Object> target) {
		if (source == null) {
			return;
		}
		if (source.containsKey("objectKey")) {
			target.put("objectKey", source.get("objectKey"));
		}
		if (source.containsKey("file")) {
			target.put("file", source.get("file"));
		}
	}

	private static String text(Object value) {
		if (value == null) {
			return null;
		}
		String text = value.toString().trim();
		return text.isEmpty() ? null : text;
	}
}
