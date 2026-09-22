package com.etp.migration.connector.source;

import com.etp.migration.processing.SourceRecord;
import java.util.Map;
import java.util.stream.Stream;

public record SourceBatch(
		int batchNumber,
		Map<String, Object> sourceLocator,
		Stream<SourceRecord> records
) implements AutoCloseable {

	@Override
	public void close() {
		if (records != null) {
			records.close();
		}
	}
}
