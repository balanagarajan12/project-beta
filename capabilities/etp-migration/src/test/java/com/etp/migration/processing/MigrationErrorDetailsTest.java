package com.etp.migration.processing;

import static org.assertj.core.api.Assertions.assertThat;

import com.etp.migration.connector.target.TargetInfrastructureException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

class MigrationErrorDetailsTest {

	private static final UUID RUN_ID = UUID.fromString("e8605f4b-68d3-4f00-a7c5-85cae251f504");

	@Test
	void nestedPostgreSqlCauseKeepsSqlStateAndRelationAndDropsRowText() {
		PSQLException database = new PSQLException(new ServerErrorMessage(
				"SERROR\0C42P01\0Mrelation \"migration_staging.missing_load\" does not exist\0"
						+ "DFailing row contains (secret-row).\0"));
		TargetInfrastructureException infrastructure = new TargetInfrastructureException(
				MigrationErrorCodes.TARGET_COPY_FAILED, "PostgreSQL COPY failed", database);
		BatchProcessingException failure = new BatchProcessingException(
				MigrationErrorCodes.TARGET_COPY_FAILED, "Target load failed", infrastructure);

		Map<String, Object> details = MigrationErrorDetails.collect(
				failure,
				MigrationErrorCodes.TARGET_COPY_FAILED,
				"LOAD",
				RUN_ID,
				1L,
				"address",
				true);

		assertThat(details)
				.containsEntry("errorCode", MigrationErrorCodes.TARGET_COPY_FAILED)
				.containsEntry("sqlState", "42P01")
				.containsEntry("condition", "undefined_table")
				.containsEntry("relation", "migration_staging.missing_load")
				.containsEntry("exceptionType", BatchProcessingException.class.getName())
				.containsEntry("message", "Target load failed")
				.containsEntry("rootCauseType", PSQLException.class.getName())
				.containsEntry("rootCauseMessage", "relation \"migration_staging.missing_load\" does not exist")
				.containsEntry("phase", "LOAD")
				.containsEntry("runId", RUN_ID.toString())
				.containsEntry("batchId", 1L)
				.containsEntry("targetEntity", "address")
				.containsEntry("retryable", true);
		assertThat(details).doesNotContainKeys("schema", "table", "column", "constraint");
		assertThat(details.toString()).doesNotContain("secret-row", "Failing row");
	}

	@Test
	void absentOptionalFieldsAreOmitted() {
		Map<String, Object> details = MigrationErrorDetails.collect(
				new IllegalStateException("planning failed"),
				MigrationErrorCodes.PREPARATION_FAILED,
				"PREPARE",
				RUN_ID,
				null,
				" ",
				false);

		assertThat(details)
				.containsEntry("errorCode", MigrationErrorCodes.PREPARATION_FAILED)
				.containsEntry("phase", "PREPARE")
				.containsEntry("exceptionType", IllegalStateException.class.getName())
				.containsEntry("rootCauseType", IllegalStateException.class.getName())
				.containsEntry("rootCauseMessage", "planning failed")
				.containsEntry("message", "planning failed")
				.containsEntry("retryable", false)
				.doesNotContainKeys("sqlState", "relation", "batchId", "targetEntity", "table");
	}

	@Test
	void credentialTextIsNotStored() {
		Map<String, Object> details = MigrationErrorDetails.collect(
				new IllegalStateException("password=hunter2"),
				MigrationErrorCodes.PREPARATION_FAILED,
				"PREPARE",
				null,
				null,
				null,
				null);

		assertThat(details)
				.containsEntry("exceptionType", IllegalStateException.class.getName())
				.doesNotContainKeys("message", "rootCauseMessage", "sqlState", "runId");
		assertThat(details.toString()).doesNotContain("hunter2");
	}

	@Test
	void nullMessageUsesTheApplicationFrame() {
		NullPointerException failure = new NullPointerException();
		failure.setStackTrace(new StackTraceElement[] {
				new StackTraceElement(
						"com.etp.migration.processing.MigrationBatchWorker",
						"processOwnedBatch",
						"MigrationBatchWorker.java",
						174)
		});

		String diagnostic = MigrationErrorDetails.diagnostic(failure);

		assertThat(diagnostic).isEqualTo(
				"java.lang.NullPointerException at com.etp.migration.processing.MigrationBatchWorker"
						+ ".processOwnedBatch(MigrationBatchWorker.java:174)");
		assertThat(MigrationErrorDetails.summary(failure)).isEqualTo(diagnostic);
		assertThat(MigrationErrorDetails.publicDetails(Map.of(
				"stackTrace", diagnostic,
				"sqlState", "23503",
				"message", "password=hunter2")))
				.containsOnlyKeys("sqlState")
				.containsEntry("sqlState", "23503");
		assertThat(MigrationErrorDetails.publicMessage("token=abc api_key=hidden"))
				.isEqualTo("Error details were redacted");
		assertThat(MigrationErrorDetails.publicMessage("city is required")).isEqualTo("city is required");
	}

	@Test
	void publicViewRedactsNestedValuesAndLeavesTheStoredObjectAlone() {
		Map<String, Object> inner = new LinkedHashMap<>();
		inner.put("note", "password=hunter2");
		inner.put("stage", "VALIDATE");
		List<Object> rows = new ArrayList<>();
		rows.add(Map.of("value", "secret=row", "field", "city"));
		rows.add("bearer token");
		rows.add("addr-41");
		Map<String, Object> stored = new LinkedHashMap<>();
		stored.put("category", "RECORD");
		stored.put("location", "com.etp.migration.processing.MigrationBatchWorker.processOwnedBatch");
		stored.put("context", inner);
		stored.put("rows", rows);
		stored.put("stackTrace", "at hidden.Frame.line");

		Map<String, Object> published = MigrationErrorDetails.publicDetails(stored);

		assertThat(published)
				.containsEntry("category", "RECORD")
				.containsEntry("location", "com.etp.migration.processing.MigrationBatchWorker.processOwnedBatch")
				.doesNotContainKey("stackTrace");
		assertThat(published.get("context")).isEqualTo(Map.of("stage", "VALIDATE"));
		assertThat(published.get("rows")).isEqualTo(List.of(Map.of("field", "city"), "addr-41"));
		assertThat(inner).containsEntry("note", "password=hunter2");
		assertThat(rows).hasSize(3);
		assertThat(stored).containsKey("stackTrace");
		assertThat(MigrationErrorDetails.publicSourceKey("addr-41")).isEqualTo("addr-41");
		assertThat(MigrationErrorDetails.publicSourceKey("password")).isEqualTo("password");
		assertThat(MigrationErrorDetails.publicSourceKey("password=hunter2")).isEqualTo("redacted");
		assertThat(MigrationErrorDetails.publicSourceKey("key (id)=(1)")).isEqualTo("redacted");
	}
}
