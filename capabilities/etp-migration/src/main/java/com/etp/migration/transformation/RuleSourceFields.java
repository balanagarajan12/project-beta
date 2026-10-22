package com.etp.migration.transformation;

import com.etp.migration.entity.MigrationMappingRule;
import com.etp.migration.entity.TransformationType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Columns a mapping rule reads from the source row.
 * {@code configuration.sourceFields} is the list when it is present.
 * A CONCAT {@code sourceField} of {@code street_name,city} is those two columns.
 * Every other rule uses {@code sourceField} as one column name.
 */
public final class RuleSourceFields {

	private RuleSourceFields() {
	}

	public static List<String> columns(MigrationMappingRule rule) {
		if (rule == null) {
			return List.of();
		}
		List<String> configured = configured(rule.getConfiguration());
		if (!configured.isEmpty()) {
			return configured;
		}
		List<String> fields = new ArrayList<>();
		String sourceField = rule.getSourceField();
		if (sourceField != null && !sourceField.isBlank()) {
			if (rule.getTransformationType() == TransformationType.CONCAT) {
				fields.addAll(split(sourceField));
			} else {
				add(fields, sourceField);
			}
		}
		if (rule.getTransformationType() == TransformationType.CONCAT) {
			for (String field : ConcatExpression.fieldNames(rule.getTransformationExpression())) {
				add(fields, field);
			}
		}
		return List.copyOf(fields);
	}

	private static List<String> configured(Map<String, Object> configuration) {
		if (configuration == null) {
			return List.of();
		}
		Object listed = configuration.get("sourceFields");
		if (!(listed instanceof List<?> values)) {
			return List.of();
		}
		List<String> fields = new ArrayList<>();
		for (Object value : values) {
			if (value != null) {
				add(fields, String.valueOf(value));
			}
		}
		return List.copyOf(fields);
	}

	private static List<String> split(String sourceField) {
		List<String> fields = new ArrayList<>();
		for (String part : sourceField.split(",", -1)) {
			add(fields, part);
		}
		if (fields.isEmpty()) {
			return List.of(sourceField.trim());
		}
		return List.copyOf(fields);
	}

	private static void add(List<String> fields, String text) {
		if (text == null) {
			return;
		}
		String trimmed = text.trim();
		if (!trimmed.isEmpty()) {
			fields.add(trimmed);
		}
	}
}
