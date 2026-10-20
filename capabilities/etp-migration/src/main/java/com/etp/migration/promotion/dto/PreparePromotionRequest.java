package com.etp.migration.promotion.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record PreparePromotionRequest(
		@Schema(description = "Ignored. requestedBy is taken from the authenticated token.", deprecated = true)
		String requestedBy) {
}
