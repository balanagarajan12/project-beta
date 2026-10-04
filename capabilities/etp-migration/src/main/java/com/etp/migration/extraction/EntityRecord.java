package com.etp.migration.extraction;

import com.etp.migration.processing.SourceRecord;
import java.util.Map;

/**
 * One logical entity taken from a physical source row. Field names are configuration, not a business type.
 */
public record EntityRecord(
		String entityName,
		long sourceRecordNumber,
		String sourceIdentity,
		Map<String, Object> fields
) {
	public EntityRecord {
		fields = fields == null ? Map.of() : Map.copyOf(fields);
	}

	public SourceRecord toSourceRecord() {
		return new SourceRecord(sourceRecordNumber, fields);
	}
}
