package com.etp.migration.connector.target.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.etp.migration.connector.target.InvalidPromotionConfigurationException;
import com.etp.migration.entity.PromotionStrategy;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PromotionTargetConfigurationTest {

	@Test
	void existingConfigurationWithoutPromotionObjectRemainsValid() {
		Map<String, Object> configuration = Map.of(
				"target", Map.of(
						"schema", "migration_staging",
						"stagingTable", "address_load",
						"mergeStrategy", "NONE"));

		PromotionTargetConfiguration parsed = PromotionTargetConfiguration.parse(configuration);

		assertThat(parsed).isEqualTo(PromotionTargetConfiguration.DISABLED);
		assertThat(parsed.enabled()).isFalse();
	}

	@Test
	void promotionDisabledDoesNotRequirePromotionFields() {
		Map<String, Object> configuration = configuration(Map.of("enabled", false));

		PromotionTargetConfiguration parsed = PromotionTargetConfiguration.parse(configuration);

		assertThat(parsed.enabled()).isFalse();
		assertThat(parsed.schema()).isNull();
		assertThat(parsed.table()).isNull();
		assertThat(parsed.strategy()).isNull();
	}

	@Test
	void validInsertConfigurationIsAccepted() {
		Map<String, Object> promotion = enabledPromotion();
		promotion.put("strategy", "INSERT");
		promotion.remove("conflictKeys");

		PromotionTargetConfiguration parsed = PromotionTargetConfiguration.parse(configuration(promotion));

		assertThat(parsed.enabled()).isTrue();
		assertThat(parsed.schema()).isEqualTo("uam");
		assertThat(parsed.table()).isEqualTo("address");
		assertThat(parsed.strategy()).isEqualTo(PromotionStrategy.INSERT);
		assertThat(parsed.columns()).containsEntry("external_id", "external_id");
		assertThat(parsed.conflictKeys()).isEmpty();
	}

	@Test
	void validUpsertConfigurationIsAccepted() {
		PromotionTargetConfiguration parsed = PromotionTargetConfiguration.parse(configuration(enabledPromotion()));

		assertThat(parsed.enabled()).isTrue();
		assertThat(parsed.strategy()).isEqualTo(PromotionStrategy.UPSERT);
		assertThat(parsed.conflictKeys()).containsExactly("external_id");
		assertThat(parsed.columns()).containsEntry("city", "city");
	}

	@Test
	void missingPromotionSchemaIsRejected() {
		Map<String, Object> promotion = enabledPromotion();
		promotion.remove("schema");
		assertRejected(promotion, "Promotion schema is required");
	}

	@Test
	void missingPromotionTableIsRejected() {
		Map<String, Object> promotion = enabledPromotion();
		promotion.remove("table");
		assertRejected(promotion, "Promotion table is required");
	}

	@Test
	void missingPromotionStrategyIsRejected() {
		Map<String, Object> promotion = enabledPromotion();
		promotion.remove("strategy");
		assertRejected(promotion, "Promotion strategy is required");
	}

	@Test
	void emptyColumnsAreRejected() {
		Map<String, Object> promotion = enabledPromotion();
		promotion.put("columns", Map.of());
		assertRejected(promotion, "Promotion columns are required");
	}

	@Test
	void upsertWithoutConflictKeysIsRejected() {
		Map<String, Object> promotion = enabledPromotion();
		promotion.remove("conflictKeys");
		assertRejected(promotion, "Promotion conflictKeys are required for UPSERT");
	}

	@Test
	void conflictKeyNotPresentInColumnValuesIsRejected() {
		Map<String, Object> promotion = enabledPromotion();
		promotion.put("conflictKeys", List.of("id"));
		assertRejected(promotion, "Promotion conflictKeys must exist in promotion.columns values");
	}

	@Test
	void invalidSchemaIdentifierIsRejected() {
		Map<String, Object> promotion = enabledPromotion();
		promotion.put("schema", "uam-core");
		assertRejected(promotion, "Promotion schema is invalid");
	}

	@Test
	void invalidTableIdentifierIsRejected() {
		Map<String, Object> promotion = enabledPromotion();
		promotion.put("table", "address;drop");
		assertRejected(promotion, "Promotion table is invalid");
	}

	@Test
	void invalidColumnIdentifierIsRejected() {
		Map<String, Object> promotion = enabledPromotion();
		promotion.put("columns", Map.of("city", "city name"));
		assertRejected(promotion, "Promotion column identifier is invalid");
	}

	@Test
	void unsupportedPromotionStrategyIsRejected() {
		Map<String, Object> promotion = enabledPromotion();
		promotion.put("strategy", "MERGE");
		assertRejected(promotion, "Unsupported promotion strategy");
	}

	@Test
	void sqlExpressionsAreRejectedAsIdentifiers() {
		Map<String, Object> promotion = enabledPromotion();
		promotion.put("table", "address\" FROM address; --");
		assertRejected(promotion, "Promotion table is invalid");
	}

	private static void assertRejected(Map<String, Object> promotion, String message) {
		assertThatThrownBy(() -> PromotionTargetConfiguration.parse(configuration(promotion)))
				.isInstanceOf(InvalidPromotionConfigurationException.class)
				.hasMessage(message);
	}

	private static Map<String, Object> configuration(Map<String, Object> promotion) {
		Map<String, Object> configuration = new LinkedHashMap<>();
		configuration.put("target", Map.of(
				"schema", "migration_staging",
				"stagingTable", "address_load",
				"mergeStrategy", "NONE"));
		configuration.put("promotion", promotion);
		return configuration;
	}

	private static Map<String, Object> enabledPromotion() {
		Map<String, Object> promotion = new HashMap<>();
		promotion.put("enabled", true);
		promotion.put("schema", "uam");
		promotion.put("table", "address");
		promotion.put("strategy", "UPSERT");
		Map<String, String> columns = new LinkedHashMap<>();
		columns.put("external_id", "external_id");
		columns.put("address_line_1", "address_line_1");
		columns.put("city", "city");
		columns.put("state", "state");
		columns.put("postal_code", "postal_code");
		promotion.put("columns", columns);
		promotion.put("conflictKeys", List.of("external_id"));
		return promotion;
	}
}
