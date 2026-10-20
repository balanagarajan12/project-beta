package com.etp.migration.migration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MigrationModelRulesTest {

	@Test
	void singleEntityRequiresTargetEntity() {
		assertThatThrownBy(() -> MigrationModelRules.validate(null, Map.of()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("definition.targetEntity is required for a single-entity migration");
		assertThatCode(() -> MigrationModelRules.validate("address", Map.of("notes", "flat")))
				.doesNotThrowAnyException();
	}

	@Test
	void entityRelationshipAllowsAMissingTargetEntity() {
		assertThatCode(() -> MigrationModelRules.validate(null, relationship("GeographicAddress")))
				.doesNotThrowAnyException();
		assertThatCode(() -> MigrationModelRules.validate("geographic_address", relationship("GeographicAddress")))
				.doesNotThrowAnyException();
		assertThatCode(() -> MigrationModelRules.validate("staged_entity", relationship("GeographicAddress")))
				.doesNotThrowAnyException();
	}

	@Test
	void entityRelationshipRejectsATargetEntityThatNamesAnotherEntity() {
		assertThatThrownBy(() -> MigrationModelRules.validate("GeographicSubAddress", relationship("GeographicAddress")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("definition.targetEntity 'GeographicSubAddress' contradicts root entity 'GeographicAddress'");
		assertThatThrownBy(() -> MigrationModelRules.validate("geographic_sub_address", relationship("GeographicAddress")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("definition.targetEntity 'geographic_sub_address' contradicts root entity 'GeographicAddress'");
	}

	private static Map<String, Object> relationship(String root) {
		return Map.of(
				"modelType", "ENTITY_RELATIONSHIP",
				"rootEntity", root,
				"entities", List.of(
						Map.of("name", "GeographicAddress", "targetEntity", "geographic_address", "targetKey", "location_id"),
						Map.of("entity", "GeographicSubAddress", "targetEntity", "geographic_sub_address", "targetKey", "id")),
				"relationships", List.of(Map.of(
						"parent", "GeographicAddress",
						"child", "GeographicSubAddress",
						"cardinality", "ONE_TO_MANY",
						"targetForeignKey", Map.of("column", "address_id", "referencesColumn", "location_id"))));
	}
}
