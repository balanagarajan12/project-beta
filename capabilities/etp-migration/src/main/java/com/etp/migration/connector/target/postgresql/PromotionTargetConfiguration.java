package com.etp.migration.connector.target.postgresql;

import com.etp.migration.connector.target.InvalidPromotionConfigurationException;
import com.etp.migration.entity.PromotionStrategy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record PromotionTargetConfiguration(
		boolean enabled,
		String schema,
		String table,
		PromotionStrategy strategy,
		Map<String, String> columns,
		List<String> conflictKeys) {

	public static final PromotionTargetConfiguration DISABLED =
			new PromotionTargetConfiguration(false, null, null, null, Map.of(), List.of());

	@SuppressWarnings("unchecked")
	public static PromotionTargetConfiguration parse(Map<String, Object> configuration) {
		if (configuration == null || !configuration.containsKey("promotion")) {
			return DISABLED;
		}
		Object promotionValue = configuration.get("promotion");
		if (!(promotionValue instanceof Map<?, ?> promotionMapRaw)) {
			throw new InvalidPromotionConfigurationException("Promotion configuration must be an object");
		}
		Map<String, Object> promotion = (Map<String, Object>) promotionMapRaw;
		if (!enabled(promotion)) {
			return DISABLED;
		}

		String schema = requiredText(promotion.get("schema"), "Promotion schema is required");
		String table = requiredText(promotion.get("table"), "Promotion table is required");
		PromotionStrategy strategy = parseStrategy(promotion.get("strategy"));
		Map<String, String> columns = parseColumns(promotion.get("columns"));
		List<String> conflictKeys = parseConflictKeys(promotion.get("conflictKeys"));

		requireIdentifier(schema, "Promotion schema is invalid");
		requireIdentifier(table, "Promotion table is invalid");
		for (String column : columns.values()) {
			requireIdentifier(column, "Promotion column identifier is invalid");
		}

		if (strategy == PromotionStrategy.UPSERT) {
			if (conflictKeys.isEmpty()) {
				throw new InvalidPromotionConfigurationException("Promotion conflictKeys are required for UPSERT");
			}
			for (String conflictKey : conflictKeys) {
				requireIdentifier(conflictKey, "Promotion conflict key identifier is invalid");
				if (!columns.containsValue(conflictKey)) {
					throw new InvalidPromotionConfigurationException(
							"Promotion conflictKeys must exist in promotion.columns values");
				}
			}
		}

		return new PromotionTargetConfiguration(
				true,
				schema,
				table,
				strategy,
				Map.copyOf(columns),
				List.copyOf(conflictKeys));
	}

	private static boolean enabled(Map<String, Object> promotion) {
		Object enabled = promotion.get("enabled");
		if (enabled == null) {
			return false;
		}
		if (enabled instanceof Boolean bool) {
			return bool;
		}
		return Boolean.parseBoolean(enabled.toString().trim());
	}

	private static PromotionStrategy parseStrategy(Object value) {
		String strategy = requiredText(value, "Promotion strategy is required");
		try {
			return PromotionStrategy.valueOf(strategy.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException ex) {
			throw new InvalidPromotionConfigurationException("Unsupported promotion strategy");
		}
	}

	private static Map<String, String> parseColumns(Object value) {
		if (!(value instanceof Map<?, ?> raw) || raw.isEmpty()) {
			throw new InvalidPromotionConfigurationException("Promotion columns are required");
		}
		Map<String, String> columns = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : raw.entrySet()) {
			if (entry.getKey() == null || entry.getValue() == null) {
				throw new InvalidPromotionConfigurationException("Promotion columns are required");
			}
			String column = entry.getValue().toString().trim();
			if (column.isEmpty()) {
				throw new InvalidPromotionConfigurationException("Promotion columns are required");
			}
			columns.put(entry.getKey().toString(), column);
		}
		if (columns.isEmpty()) {
			throw new InvalidPromotionConfigurationException("Promotion columns are required");
		}
		return columns;
	}

	private static List<String> parseConflictKeys(Object value) {
		if (value == null) {
			return List.of();
		}
		if (!(value instanceof List<?> raw)) {
			throw new InvalidPromotionConfigurationException("Promotion conflictKeys must be a list");
		}
		List<String> keys = new ArrayList<>();
		for (Object item : raw) {
			if (item == null || item.toString().trim().isEmpty()) {
				throw new InvalidPromotionConfigurationException("Promotion conflictKeys must be non-empty for UPSERT");
			}
			keys.add(item.toString().trim());
		}
		return keys;
	}

	private static String requiredText(Object value, String message) {
		if (value == null) {
			throw new InvalidPromotionConfigurationException(message);
		}
		String text = value.toString().trim();
		if (text.isEmpty()) {
			throw new InvalidPromotionConfigurationException(message);
		}
		return text;
	}

	private static void requireIdentifier(String value, String message) {
		if (!PostgreSqlIdentifiers.isValid(value)) {
			throw new InvalidPromotionConfigurationException(message);
		}
	}
}
