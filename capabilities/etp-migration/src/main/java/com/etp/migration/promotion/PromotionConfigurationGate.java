package com.etp.migration.promotion;

import com.etp.migration.connector.target.postgresql.PromotionTargetConfiguration;
import com.etp.migration.connector.target.uam.UamStagingPromotion;
import com.etp.migration.processing.MigrationErrorCodes;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Flat promotions keep the existing table configuration.
 * A canonical target mode still requires promotion to be enabled, without a single flat table.
 */
final class PromotionConfigurationGate {

	private PromotionConfigurationGate() {
	}

	static PromotionTargetConfiguration requireEnabled(Map<String, Object> configuration) {
		if (UamStagingPromotion.configured(configuration)) {
			requireCanonicalPromotionEnabled(configuration);
			return null;
		}
		return requireFlatPromotion(configuration);
	}

	private static void requireCanonicalPromotionEnabled(Map<String, Object> configuration) {
		if (configuration == null || !(configuration.get("promotion") instanceof Map<?, ?>)) {
			throw new PromotionException(
					HttpStatus.BAD_REQUEST.value(),
					MigrationErrorCodes.PROMOTION_NOT_CONFIGURED,
					"Promotion is not configured");
		}
		if (!UamStagingPromotion.promotionEnabled(configuration)) {
			throw new PromotionException(
					HttpStatus.BAD_REQUEST.value(),
					MigrationErrorCodes.PROMOTION_DISABLED,
					"Promotion is disabled");
		}
	}

	private static PromotionTargetConfiguration requireFlatPromotion(Map<String, Object> configuration) {
		if (configuration == null || !configuration.containsKey("promotion")) {
			throw new PromotionException(
					HttpStatus.BAD_REQUEST.value(),
					MigrationErrorCodes.PROMOTION_NOT_CONFIGURED,
					"Promotion is not configured");
		}
		PromotionTargetConfiguration parsed = PromotionTargetConfiguration.parse(configuration);
		if (!parsed.enabled()) {
			throw new PromotionException(
					HttpStatus.BAD_REQUEST.value(),
					MigrationErrorCodes.PROMOTION_DISABLED,
					"Promotion is disabled");
		}
		return parsed;
	}
}
