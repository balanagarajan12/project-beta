package com.etp.migration.relationship;

import com.etp.migration.extraction.EntityExtractionFailure;
import java.util.List;

/**
 * Extraction failures stay separate from relationship failures. This is not a migration scope.
 */
public record SourceRelationshipResult(
		List<EntityExtractionFailure> extractionFailures,
		RelationshipResolution resolution
) {
	public SourceRelationshipResult {
		extractionFailures = extractionFailures == null ? List.of() : List.copyOf(extractionFailures);
	}
}
