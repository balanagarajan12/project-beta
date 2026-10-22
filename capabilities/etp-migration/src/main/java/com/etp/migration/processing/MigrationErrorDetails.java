package com.etp.migration.processing;

import com.etp.migration.connector.target.TargetFailureDiagnostics;
import com.etp.migration.connector.target.TargetFailureDiagnostics.SqlFacts;
import com.etp.migration.connector.target.TargetInfrastructureException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.postgresql.util.PSQLException;

/**
 * Structured {@code migration_error.error_details}. Fields are omitted when they are not known.
 * Database detail and hint text are not stored, because PostgreSQL can quote source values there.
 */
public final class MigrationErrorDetails {

	private static final int MAX_TEXT = 500;
	private static final int MAX_DEPTH = 16;
	private static final Set<String> IDENTITY_FIELDS = Set.of(
			"sourceidentity",
			"sourcerecordkey",
			"fieldname",
			"parentsourceidentity",
			"resolvedtargetidentity");
	private static final Set<String> MESSAGE_FIELDS = Set.of(
			"reason",
			"message",
			"errormessage",
			"rootcausemessage");

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

	/**
	 * Text safe to store. A database failure keeps the structural summary used on a promotion batch. A null or blank
	 * message becomes the exception class and first application frame. A message that would quote a secret or a row
	 * value becomes the exception class only.
	 */
	public static String summary(Throwable failure) {
		if (failure != null
				&& !(failure instanceof TargetInfrastructureException)
				&& TargetFailureDiagnostics.involvesDatabase(failure)) {
			return "Promotion batch failed: " + TargetFailureDiagnostics.describe(failure);
		}
		return diagnostic(failure);
	}

	/**
	 * Safe text for an execution batch, phase job, or preparation failure. A nonblank message that does not quote a
	 * secret or a row value is kept.
	 */
	public static String diagnostic(Throwable failure) {
		if (failure == null) {
			return "Operation failed";
		}
		String message = failure.getMessage();
		if (message != null && !message.isBlank() && !sensitive(message)) {
			return message;
		}
		if (!(failure instanceof TargetInfrastructureException) && TargetFailureDiagnostics.involvesDatabase(failure)) {
			return TargetFailureDiagnostics.describe(failure);
		}
		if (message == null || message.isBlank()) {
			return TargetFailureDiagnostics.location(failure);
		}
		return failure.getClass().getName();
	}

	/** Record-level text. A missing or sensitive message becomes a fixed label. */
	public static String recordMessage(String message) {
		if (message == null || message.isBlank() || sensitive(message)) {
			return "Record processing failed";
		}
		return message;
	}

	public static String publicMessage(String message) {
		if (message == null || !sensitive(message)) {
			return message;
		}
		return "Error details were redacted";
	}

	/**
	 * Public form of a source key or field name. A value that carries a secret or a quoted row is replaced.
	 * A short identifier is kept, including a column name that is only the word {@code password}.
	 */
	public static String publicSourceKey(String value) {
		if (value == null || !exposesSecret(value)) {
			return value;
		}
		return "redacted";
	}

	/**
	 * Public copy of a reconciliation details document. Identity fields use {@link #publicSourceKey}.
	 * Message fields use {@link #publicMessage}. Counts, status, entity names, and checksums are copied.
	 * The stored map is not modified.
	 */
	public static Map<String, Object> publicReconciliationDetails(Map<String, Object> details) {
		if (details == null || details.isEmpty()) {
			return Map.of();
		}
		Object copy = publishDocument(details, 0);
		if (copy instanceof Map<?, ?> map) {
			Map<String, Object> published = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				published.put(String.valueOf(entry.getKey()), entry.getValue());
			}
			return published;
		}
		return Map.of();
	}

	public static Map<String, Object> publicDetails(Map<String, Object> details) {
		if (details == null || details.isEmpty()) {
			return Map.of();
		}
		Object copy = copyValue(details, 0);
		if (copy instanceof Map<?, ?> map) {
			Map<String, Object> published = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				published.put(String.valueOf(entry.getKey()), entry.getValue());
			}
			return published;
		}
		return Map.of();
	}

	public static boolean sensitive(String value) {
		if (value == null || value.isBlank()) {
			return false;
		}
		String lower = value.toLowerCase(Locale.ROOT);
		return lower.contains("failing row")
				|| lower.contains("key (")
				|| lower.contains("password")
				|| lower.contains("private key")
				|| lower.contains("begin openssh")
				|| lower.contains("token=")
				|| lower.contains("bearer ")
				|| lower.contains("api_key")
				|| lower.contains("api-key")
				|| lower.contains("secret=");
	}

	static String safe(String value) {
		if (value == null || value.isBlank() || sensitive(value)) {
			return null;
		}
		return value.length() <= MAX_TEXT ? value : value.substring(0, MAX_TEXT);
	}

	private static boolean sensitiveKey(String key) {
		String lower = key.toLowerCase(Locale.ROOT);
		return lower.contains("stack")
				|| lower.contains("password")
				|| lower.contains("secret")
				|| lower.contains("credential")
				|| lower.contains("privatekey")
				|| lower.contains("private_key");
	}

	/**
	 * A short identifier is not treated as a leaked secret. Assignment text, quoted rows, and multi-word secret
	 * material are.
	 */
	static boolean exposesSecret(String value) {
		if (!sensitive(value)) {
			return false;
		}
		String trimmed = value.trim();
		return trimmed.contains(" ")
				|| trimmed.contains("=")
				|| trimmed.contains("(")
				|| trimmed.contains("\n");
	}

	private static Object copyValue(Object value, int depth) {
		if (value == null || depth > MAX_DEPTH) {
			return null;
		}
		if (value instanceof String text) {
			return sensitive(text) ? null : text;
		}
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> nested = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				if (entry.getKey() == null || sensitiveKey(entry.getKey().toString())) {
					continue;
				}
				Object nestedValue = copyValue(entry.getValue(), depth + 1);
				if (nestedValue != null) {
					nested.put(entry.getKey().toString(), nestedValue);
				}
			}
			return nested;
		}
		if (value instanceof Iterable<?> iterable && !(value instanceof Map<?, ?>)) {
			List<Object> copy = new ArrayList<>();
			for (Object item : iterable) {
				Object nested = copyValue(item, depth + 1);
				if (nested != null) {
					copy.add(nested);
				}
			}
			return copy;
		}
		return value;
	}

	private static Object publishDocument(Object value, int depth) {
		if (value == null || depth > MAX_DEPTH) {
			return null;
		}
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> nested = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				if (entry.getKey() == null) {
					continue;
				}
				String key = entry.getKey().toString();
				Object raw = entry.getValue();
				if (raw == null) {
					nested.put(key, null);
					continue;
				}
				String lower = key.toLowerCase(Locale.ROOT);
				if (raw instanceof String text && IDENTITY_FIELDS.contains(lower)) {
					nested.put(key, publicSourceKey(text));
					continue;
				}
				if (raw instanceof String text && MESSAGE_FIELDS.contains(lower)) {
					nested.put(key, publicMessage(text));
					continue;
				}
				Object nestedValue = publishDocument(raw, depth + 1);
				if (nestedValue != null) {
					nested.put(key, nestedValue);
				}
			}
			return nested;
		}
		if (value instanceof Iterable<?> iterable) {
			List<Object> copy = new ArrayList<>();
			for (Object item : iterable) {
				Object nested = publishDocument(item, depth + 1);
				if (nested != null) {
					copy.add(nested);
				}
			}
			return copy;
		}
		return value;
	}
}
