package com.etp.migration.promotion;

import java.util.UUID;

/**
 * The worker observed a pause at a batch boundary. The current batch has already committed.
 * The phase job stays non-terminal so resume can claim it again.
 */
public class PromotionPausedException extends RuntimeException {

	public PromotionPausedException(UUID runId) {
		super("Promotion paused for migration run " + runId);
	}
}
