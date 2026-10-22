package com.etp.migration.transformation;

import com.etp.migration.entity.MigrationMappingRule;
import com.etp.migration.entity.TransformationType;
import com.etp.migration.processing.MigrationErrorCodes;
import com.etp.migration.processing.SourceRecord;
import com.etp.migration.processing.TransformationException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.stereotype.Component;

@Component
public class TransformationEngine {

	public void apply(MigrationMappingRule rule, SourceRecord source, Map<String, Object> target) {
		TransformationType type = rule.getTransformationType();
		if (type == TransformationType.LOOKUP || type == TransformationType.CUSTOM) {
			throw new TransformationException(
					MigrationErrorCodes.UNSUPPORTED_TRANSFORMATION,
					rule.getTargetField(),
					"Transformation type " + type + " is not supported");
		}
		switch (type) {
			case DIRECT -> target.put(rule.getTargetField(), sourceValue(source, rule.getSourceField()));
			case TRIM -> target.put(rule.getTargetField(), trim(sourceValue(source, rule.getSourceField())));
			case UPPERCASE -> target.put(rule.getTargetField(), changeCase(sourceValue(source, rule.getSourceField()), true));
			case LOWERCASE -> target.put(rule.getTargetField(), changeCase(sourceValue(source, rule.getSourceField()), false));
			case DEFAULT -> applyDefault(rule, source, target);
			case CONCAT -> applyConcat(rule, source, target);
			case SPLIT -> applySplit(rule, source, target);
			case REGEX -> applyRegex(rule, source, target);
			case CONDITIONAL -> applyConditional(rule, source, target);
			case DATE_FORMAT -> applyDateFormat(rule, source, target);
			default -> throw new TransformationException(
					MigrationErrorCodes.UNSUPPORTED_TRANSFORMATION,
					rule.getTargetField(),
					"Transformation type " + type + " is not supported");
		}
	}

	private void applyDefault(MigrationMappingRule rule, SourceRecord source, Map<String, Object> target) {
		Object value = sourceValue(source, rule.getSourceField());
		if (isBlank(value)) {
			target.put(rule.getTargetField(), rule.getDefaultValue());
		} else {
			target.put(rule.getTargetField(), value);
		}
	}

	private void applyConcat(MigrationMappingRule rule, SourceRecord source, Map<String, Object> target) {
		String expression = rule.getTransformationExpression();
		if (expression != null && !expression.isBlank()) {
			target.put(
					rule.getTargetField(),
					ConcatExpression.render(expression, field -> sourceValue(source, field), rule.getTargetField()));
			return;
		}
		Map<String, Object> config = config(rule);
		List<String> sourceFields = RuleSourceFields.columns(rule);
		if (sourceFields.isEmpty()) {
			throw new TransformationException(
					MigrationErrorCodes.TRANSFORMATION_ERROR,
					rule.getTargetField(),
					"CONCAT requires sourceFields");
		}
		String separator = string(config.get("separator"), "");
		List<String> parts = new ArrayList<>();
		for (String field : sourceFields) {
			Object value = sourceValue(source, field);
			parts.add(value == null ? "" : String.valueOf(value));
		}
		target.put(rule.getTargetField(), String.join(separator, parts));
	}

	private void applySplit(MigrationMappingRule rule, SourceRecord source, Map<String, Object> target) {
		Map<String, Object> config = config(rule);
		String delimiter = string(config.get("delimiter"), null);
		List<String> targetFields = stringList(config, "targetFields");
		if (delimiter == null || delimiter.isEmpty() || targetFields.isEmpty()) {
			throw new TransformationException(
					MigrationErrorCodes.TRANSFORMATION_ERROR,
					rule.getTargetField(),
					"SPLIT requires delimiter and targetFields");
		}
		Object value = sourceValue(source, rule.getSourceField());
		if (value == null) {
			for (String field : targetFields) {
				target.put(field, null);
			}
			return;
		}
		String[] parts = String.valueOf(value).split(Pattern.quote(delimiter), -1);
		for (int i = 0; i < targetFields.size(); i++) {
			String part = i < parts.length ? parts[i].trim() : null;
			target.put(targetFields.get(i), part != null && part.isEmpty() ? "" : part);
		}
	}

	private void applyRegex(MigrationMappingRule rule, SourceRecord source, Map<String, Object> target) {
		Map<String, Object> config = config(rule);
		String patternText = string(config.get("pattern"), null);
		if (patternText == null) {
			throw new TransformationException(
					MigrationErrorCodes.TRANSFORMATION_ERROR,
					rule.getTargetField(),
					"REGEX requires pattern");
		}
		String replacement = string(config.get("replacement"), "");
		Object value = sourceValue(source, rule.getSourceField());
		if (value == null) {
			target.put(rule.getTargetField(), null);
			return;
		}
		try {
			Pattern pattern = Pattern.compile(patternText);
			target.put(rule.getTargetField(), pattern.matcher(String.valueOf(value)).replaceAll(replacement));
		} catch (PatternSyntaxException ex) {
			throw new TransformationException(
					MigrationErrorCodes.TRANSFORMATION_ERROR,
					rule.getTargetField(),
					"REGEX pattern is invalid",
					ex);
		}
	}

	private void applyConditional(MigrationMappingRule rule, SourceRecord source, Map<String, Object> target) {
		Map<String, Object> config = config(rule);
		Object conditionObj = config.get("condition");
		if (!(conditionObj instanceof Map<?, ?> condition)) {
			throw new TransformationException(
					MigrationErrorCodes.TRANSFORMATION_ERROR,
					rule.getTargetField(),
					"CONDITIONAL requires condition");
		}
		String operator = string(condition.get("operator"), "");
		String field = string(condition.get("sourceField"), rule.getSourceField());
		Object expected = condition.get("value");
		Object actual = sourceValue(source, field);
		boolean matched = evaluate(operator, actual, expected, rule.getTargetField());
		Object thenValue = config.containsKey("thenValue") ? config.get("thenValue") : null;
		Object elseValue = config.containsKey("elseValue") ? config.get("elseValue") : null;
		target.put(rule.getTargetField(), matched ? thenValue : elseValue);
	}

	private boolean evaluate(String operator, Object actual, Object expected, String targetField) {
		return switch (operator == null ? "" : operator.toUpperCase(Locale.ROOT)) {
			case "EQUALS" -> stringify(actual).equals(stringify(expected));
			case "NOT_EQUALS" -> !stringify(actual).equals(stringify(expected));
			case "IS_NULL" -> actual == null;
			case "IS_NOT_NULL" -> actual != null;
			case "IS_BLANK" -> isBlank(actual);
			case "IS_NOT_BLANK" -> !isBlank(actual);
			default -> throw new TransformationException(
					MigrationErrorCodes.TRANSFORMATION_ERROR,
					targetField,
					"CONDITIONAL operator is not supported");
		};
	}

	private void applyDateFormat(MigrationMappingRule rule, SourceRecord source, Map<String, Object> target) {
		Map<String, Object> config = config(rule);
		String sourceFormat = string(config.get("sourceFormat"), null);
		String targetFormat = string(config.get("targetFormat"), null);
		if (sourceFormat == null || targetFormat == null) {
			throw new TransformationException(
					MigrationErrorCodes.TRANSFORMATION_ERROR,
					rule.getTargetField(),
					"DATE_FORMAT requires sourceFormat and targetFormat");
		}
		Object value = sourceValue(source, rule.getSourceField());
		if (isBlank(value)) {
			target.put(rule.getTargetField(), null);
			return;
		}
		try {
			DateTimeFormatter parser = DateTimeFormatter.ofPattern(sourceFormat).withResolverStyle(ResolverStyle.SMART);
			DateTimeFormatter formatter = DateTimeFormatter.ofPattern(targetFormat);
			LocalDate date = LocalDate.parse(String.valueOf(value).trim(), parser);
			target.put(rule.getTargetField(), date.format(formatter));
		} catch (IllegalArgumentException | DateTimeParseException ex) {
			throw new TransformationException(
					MigrationErrorCodes.TRANSFORMATION_ERROR,
					rule.getTargetField(),
					"DATE_FORMAT value is invalid",
					ex);
		}
	}

	private static Object sourceValue(SourceRecord source, String field) {
		if (field == null || field.isBlank() || source.fields() == null) {
			return null;
		}
		return source.fields().get(field);
	}

	private static Object trim(Object value) {
		return value == null ? null : String.valueOf(value).trim();
	}

	private static Object changeCase(Object value, boolean upper) {
		if (value == null) {
			return null;
		}
		String text = String.valueOf(value);
		return upper ? text.toUpperCase(Locale.ROOT) : text.toLowerCase(Locale.ROOT);
	}

	private static boolean isBlank(Object value) {
		return value == null || String.valueOf(value).isBlank();
	}

	private static String stringify(Object value) {
		return value == null ? "" : String.valueOf(value);
	}

	private static Map<String, Object> config(MigrationMappingRule rule) {
		return rule.getConfiguration() == null ? Map.of() : rule.getConfiguration();
	}

	private static String string(Object value, String defaultValue) {
		if (value == null) {
			return defaultValue;
		}
		String text = value.toString();
		return text;
	}

	@SuppressWarnings("unchecked")
	private static List<String> stringList(Map<String, Object> config, String key) {
		Object value = config.get(key);
		if (value instanceof List<?> list) {
			List<String> result = new ArrayList<>();
			for (Object item : list) {
				if (item != null) {
					result.add(item.toString());
				}
			}
			return result;
		}
		return List.of();
	}
}
