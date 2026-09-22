package com.etp.migration.transformation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.etp.migration.entity.MigrationMappingRule;
import com.etp.migration.entity.TransformationType;
import com.etp.migration.processing.MigrationErrorCodes;
import com.etp.migration.processing.SourceRecord;
import com.etp.migration.processing.TransformationException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TransformationEngineTest {

	private final TransformationEngine engine = new TransformationEngine();

	@Test
	void directCopiesSourceToTarget() {
		Map<String, Object> target = new HashMap<>();
		engine.apply(rule(TransformationType.DIRECT, "addr1", "streetName"), source(), target);
		assertThat(target.get("streetName")).isEqualTo("123 Main St");
	}

	@Test
	void trimRemovesWhitespaceAndPreservesNull() {
		Map<String, Object> target = new HashMap<>();
		engine.apply(rule(TransformationType.TRIM, "padded", "streetName"), source(), target);
		assertThat(target.get("streetName")).isEqualTo("123 Main St");
		engine.apply(rule(TransformationType.TRIM, "missing", "empty"), source(), target);
		assertThat(target.get("empty")).isNull();
	}

	@Test
	void uppercaseAndLowercaseAreLocaleIndependent() {
		Map<String, Object> target = new HashMap<>();
		engine.apply(rule(TransformationType.UPPERCASE, "city", "city"), source(), target);
		assertThat(target.get("city")).isEqualTo("SALEM");
		engine.apply(rule(TransformationType.LOWERCASE, "state", "state"), source(), target);
		assertThat(target.get("state")).isEqualTo("tn");
	}

	@Test
	void defaultAppliesOnlyWhenBlank() {
		Map<String, Object> target = new HashMap<>();
		MigrationMappingRule rule = rule(TransformationType.DEFAULT, "blank", "state");
		rule.setDefaultValue("UNKNOWN");
		engine.apply(rule, source(), target);
		assertThat(target.get("state")).isEqualTo("UNKNOWN");
		rule.setSourceField("state");
		engine.apply(rule, source(), target);
		assertThat(target.get("state")).isEqualTo("TN");
	}

	@Test
	void concatJoinsConfiguredFields() {
		MigrationMappingRule rule = rule(TransformationType.CONCAT, null, "full");
		rule.setConfiguration(Map.of(
				"sourceFields", List.of("addr1", "city", "state", "zip"),
				"separator", ", "));
		Map<String, Object> target = new HashMap<>();
		engine.apply(rule, source(), target);
		assertThat(target.get("full")).isEqualTo("123 Main St, salem, TN, 636001");
	}

	@Test
	void splitWritesTrimmedTargetFields() {
		MigrationMappingRule rule = rule(TransformationType.SPLIT, "combined", "unused");
		rule.setConfiguration(Map.of(
				"delimiter", ",",
				"targetFields", List.of("streetName", "city")));
		Map<String, Object> target = new HashMap<>();
		engine.apply(rule, source(), target);
		assertThat(target.get("streetName")).isEqualTo("123 Main St");
		assertThat(target.get("city")).isEqualTo("Salem");
	}

	@Test
	void regexReplacesMatches() {
		MigrationMappingRule rule = rule(TransformationType.REGEX, "messy", "streetName");
		rule.setConfiguration(Map.of("pattern", "\\s+", "replacement", " "));
		Map<String, Object> target = new HashMap<>();
		engine.apply(rule, source(), target);
		assertThat(target.get("streetName")).isEqualTo("123 Main St");
	}

	@Test
	void conditionalSupportsDeterministicOperators() {
		MigrationMappingRule rule = rule(TransformationType.CONDITIONAL, "state", "stateName");
		rule.setConfiguration(Map.of(
				"condition", Map.of("operator", "EQUALS", "sourceField", "state", "value", "TN"),
				"thenValue", "TENNESSEE",
				"elseValue", "OTHER"));
		Map<String, Object> target = new HashMap<>();
		engine.apply(rule, source(), target);
		assertThat(target.get("stateName")).isEqualTo("TENNESSEE");
	}

	@Test
	void dateFormatConvertsUsingJavaTime() {
		MigrationMappingRule rule = rule(TransformationType.DATE_FORMAT, "opened", "openedOn");
		rule.setConfiguration(Map.of("sourceFormat", "MM/dd/yyyy", "targetFormat", "yyyy-MM-dd"));
		Map<String, Object> target = new HashMap<>();
		engine.apply(rule, source(), target);
		assertThat(target.get("openedOn")).isEqualTo("2026-09-20");
	}

	@Test
	void lookupAndCustomAreRejected() {
		assertThatThrownBy(() -> engine.apply(rule(TransformationType.LOOKUP, "city", "city"), source(), new HashMap<>()))
				.isInstanceOf(TransformationException.class)
				.extracting(ex -> ((TransformationException) ex).errorCode())
				.isEqualTo(MigrationErrorCodes.UNSUPPORTED_TRANSFORMATION);
		assertThatThrownBy(() -> engine.apply(rule(TransformationType.CUSTOM, "city", "city"), source(), new HashMap<>()))
				.isInstanceOf(TransformationException.class);
	}

	private static SourceRecord source() {
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("addr1", "123 Main St");
		fields.put("padded", "  123 Main St  ");
		fields.put("city", "salem");
		fields.put("state", "TN");
		fields.put("zip", "636001");
		fields.put("blank", "  ");
		fields.put("combined", "123 Main St, Salem");
		fields.put("messy", "123   Main    St");
		fields.put("opened", "09/20/2026");
		return new SourceRecord(1L, fields);
	}

	private static MigrationMappingRule rule(TransformationType type, String sourceField, String targetField) {
		MigrationMappingRule rule = new MigrationMappingRule();
		rule.setTransformationType(type);
		rule.setSourceField(sourceField);
		rule.setTargetField(targetField);
		rule.setEnabled(true);
		rule.setConfiguration(Map.of());
		return rule;
	}
}
