package com.etp.migration.promotion;

import java.util.UUID;

public class PromotionNotFoundException extends RuntimeException {

	public PromotionNotFoundException(UUID runId) {
		super("Promotion not found for migration run " + runId);
	}
}
