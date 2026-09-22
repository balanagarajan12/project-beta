package com.etp.migration.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.etp.migration.entity.MigrationMappingRule;
import com.etp.migration.processing.TargetRecord;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ValidationEngineTest {

	private final ValidationEngine engine = new ValidationEngine();

	@Test
	void mandatoryFieldPresentProducesNoErrors() {
		MigrationMappingRule rule = mandatory("streetName");
		assertThat(engine.validate(new TargetRecord(Map.of("streetName", "123 Main St")), List.of(rule))).isEmpty();
	}

	@Test
	void mandatoryFieldMissingProducesValidationError() {
		MigrationMappingRule rule = mandatory("streetName");
		assertThat(engine.validate(new TargetRecord(Map.of("streetName", " ")), List.of(rule))).hasSize(1);
	}

	@Test
	void multipleMandatoryFailuresAreCollected() {
		assertThat(engine.validate(
				new TargetRecord(Map.of()),
				List.of(mandatory("streetName"), mandatory("city")))).hasSize(2);
	}

	private static MigrationMappingRule mandatory(String field) {
		MigrationMappingRule rule = new MigrationMappingRule();
		rule.setTargetField(field);
		rule.setMandatory(true);
		rule.setEnabled(true);
		return rule;
	}
}
