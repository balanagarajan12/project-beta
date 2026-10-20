package com.etp.migration.processing;

import com.etp.migration.connector.target.TargetFailureDiagnostics;
import com.etp.migration.connector.target.TargetFailureDiagnostics.SqlFacts;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.postgresql.util.PSQLException;

/**
 * Structured {@code migration_error.error_details}. Fields are omitted when they are not known.
 * Database detail and hint text are not stored, because PostgreSQL can quote source values there.
 */
public final class MigrationErrorDetails {

	private static final int MAX_TEXT = 500;
	private static final int MAX_DEPTH = 16;

	private MigrationErrorDetails() {
	}

	public static Map<String, Object> collect(
			Throwable failure,
			String errorCode,
			String phase,
			UUID runId,
			Long batchId,
			String targetEntity,
			Boolean retryable) {
		Map<String, Object> details = new LinkedHashMap<>();
		put(details, "errorCode", errorCode);
		put(details, "phase", phase);
		if (runId != null) {
			details.put("runId", runId.toString());
		}
		if (batchId != null) {
			details.put("batchId", batchId);
		}
		put(details, "targetEntity", targetEntity);
		if (retryable != null) {
			details.put("retryable", retryable);
		}
		if (failure != null) {
			details.put("exceptionType", failure.getClass().getName());
			put(details, "message", safe(failure.getMessage()));
			Throwable root = rootCause(failure);
			details.put("rootCauseType", root.getClass().getName());
			put(details, "rootCauseMessage", rootMessage(failure, root));
			SqlFacts sql = TargetFailureDiagnostics.sqlFacts(failure);
			if (sql != null) {
				put(details, "sqlState", sql.sqlState());
				put(details, "condition", sql.condition());
				put(details, "schema", sql.schema());
				put(details, "table", sql.table());
				put(details, "column", sql.column());
				put(details, "constraint", sql.constraint());
				put(details, "datatype", sql.datatype());
				put(details, "relation", sql.relation());
			}
		}
		return new HashMap<>(details);
	}

	private static String rootMessage(Throwable failure, Throwable root) {
		SqlFacts sql = TargetFailureDiagnostics.sqlFacts(failure);
		if (sql != null && sql.serverMessage() != null) {
			return safe(sql.serverMessage());
		}
		if (root instanceof PSQLException) {
			return null;
		}
		return safe(root.getMessage());
	}

	private static Throwable rootCause(Throwable failure) {
		Throwable current = failure;
		for (int depth = 0; depth < MAX_DEPTH && current.getCause() != null && current.getCause() != current; depth++) {
			current = current.getCause();
		}
		return current;
	}

	private static void put(Map<String, Object> details, String key, String value) {
		String text = safe(value);
		if (text != null) {
			details.put(key, text);
		}
	}

	static String safe(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String lower = value.toLowerCase(Locale.ROOT);
		if (lower.contains("failing row")
				|| lower.contains("key (")
				|| lower.contains("password")
				|| lower.contains("private key")
				|| lower.contains("begin openssh")) {
			return null;
		}
		return value.length() <= MAX_TEXT ? value : value.substring(0, MAX_TEXT);
	}
}
