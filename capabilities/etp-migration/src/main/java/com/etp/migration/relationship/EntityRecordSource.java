package com.etp.migration.relationship;

import com.etp.migration.extraction.EntityRecord;
import java.util.stream.Stream;

/**
 * Opens one entity's records at a time. Callers must close the stream. Implementations must not retain prior entities.
 */
@FunctionalInterface
public interface EntityRecordSource {

	Stream<EntityRecord> open(String entityName);
}
