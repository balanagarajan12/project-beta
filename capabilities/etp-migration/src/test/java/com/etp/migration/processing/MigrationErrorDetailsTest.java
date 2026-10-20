package com.etp.migration.processing;

import static org.assertj.core.api.Assertions.assertThat;

import com.etp.migration.connector.target.TargetInfrastructureException;
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
}
