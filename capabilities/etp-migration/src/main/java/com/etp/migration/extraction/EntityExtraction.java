package com.etp.migration.extraction;

import java.util.List;

/**
 * Entities extracted from one physical source row. This is not a batch and not a relationship scope.
 */
public record EntityExtraction(List<EntityRecord> records, List<EntityExtractionFailure> failures) {

	public EntityExtraction {
		records = records == null ? List.of() : List.copyOf(records);
		failures = failures == null ? List.of() : List.copyOf(failures);
	}
}
