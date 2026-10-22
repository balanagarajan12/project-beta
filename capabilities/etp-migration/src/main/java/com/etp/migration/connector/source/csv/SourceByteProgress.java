package com.etp.migration.connector.source.csv;

/**
 * Reported only when bytes have been read. A live thread is not progress.
 */
@FunctionalInterface
public interface SourceByteProgress {

	void advanced(Stage stage, long bytesRead, long indexedRows);

	enum Stage {
		TRANSFER,
		INDEX
	}
}
