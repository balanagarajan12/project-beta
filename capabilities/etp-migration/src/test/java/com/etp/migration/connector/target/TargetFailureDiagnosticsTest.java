package com.etp.migration.connector.target;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.BatchUpdateException;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.ServerErrorMessage;

class TargetFailureDiagnosticsTest {

	private static PSQLException serverError(String fields) {
		return new PSQLException(new ServerErrorMessage("SERROR\0" + fields));
	}

	@Test
	void serverErrorKeepsStructuralFieldsAndDropsMessageDetailAndHint() {
		PSQLException failure = serverError("C23514\0Mnew row for relation \"location\" violates check constraint\0"
				+ "DFailing row contains (LOC-2, 10 Secret Street).\0Hcheck the value 10 Secret Street\0"
				+ "suam\0tlocation\0nck_location\0");

		String summary = TargetFailureDiagnostics.describe(new RuntimeException("wrapper", failure));

		assertThat(summary).isEqualTo("check_violation (SQLState 23514), table uam.location, constraint ck_location");
		assertThat(failure.getMessage()).contains("Secret Street");
	}

	@Test
	void invalidInputValueIsNotQuoted() {
		PSQLException failure = serverError("C22P02\0Minvalid input syntax for type numeric: \"north\"\0"
				+ "suam\0tlocation_geometry\0cx\0dnumeric\0");

		assertThat(TargetFailureDiagnostics.describe(failure))
				.isEqualTo("invalid_text_representation (SQLState 22P02), table uam.location_geometry, column x, type numeric")
				.doesNotContain("north");
	}

	@Test
	void batchUpdateUsesTheChainedServerError() {
		BatchUpdateException batch = new BatchUpdateException(
				"Batch entry 0 INSERT INTO uam.location VALUES ('LOC-1', 'secret') was aborted", "23505", new int[0]);
		batch.setNextException(serverError("C23505\0Mduplicate key value\0DKey (id)=(LOC-1) already exists.\0"
				+ "suam\0tlocation\0nlocation_pkey\0"));

		assertThat(TargetFailureDiagnostics.describe(batch))
				.isEqualTo("unique_violation (SQLState 23505), table uam.location, constraint location_pkey")
				.doesNotContain("LOC-1", "secret");
	}

	@Test
	void undefinedTableKeepsTheRelationFromTheServerMessageAndDropsTheDetail() {
		PSQLException failure = serverError("C42P01\0Mrelation \"migration_staging.missing_load\" does not exist\0"
				+ "DFailing row contains (secret-row).\0");

		TargetFailureDiagnostics.SqlFacts facts = TargetFailureDiagnostics.sqlFacts(
				new RuntimeException("wrapper", failure));

		assertThat(facts.sqlState()).isEqualTo("42P01");
		assertThat(facts.condition()).isEqualTo("undefined_table");
		assertThat(facts.relation()).isEqualTo("migration_staging.missing_load");
		assertThat(facts.serverMessage()).isEqualTo("relation \"migration_staging.missing_load\" does not exist");
		assertThat(facts.table()).isNull();
		assertThat(facts.serverMessage()).doesNotContain("secret-row");
		assertThat(TargetFailureDiagnostics.sqlFacts(new IllegalStateException("x"))).isNull();
	}

	@Test
	void driverErrorWithoutServerFieldsReportsOnlyTheCondition() {
		PSQLException refused = new PSQLException(
				"Connection to db.internal.example:5432 refused", PSQLState.CONNECTION_UNABLE_TO_CONNECT);

		assertThat(TargetFailureDiagnostics.describe(refused))
				.isEqualTo("sqlclient_unable_to_establish_sqlconnection (SQLState 08001)");
	}

	@Test
	void unknownStatesFallBackToTheirClassAndNonDatabaseFailuresToTheirType() {
		assertThat(TargetFailureDiagnostics.describe(new SQLException("x", "23999")))
				.isEqualTo("integrity_constraint_violation (SQLState 23999)");
		assertThat(TargetFailureDiagnostics.describe(new SQLException("password=hunter2")))
				.isEqualTo("database_error (SQLState unknown)");
		assertThat(TargetFailureDiagnostics.describe(new IllegalStateException("token=abc")))
				.isEqualTo("IllegalStateException");
		assertThat(TargetFailureDiagnostics.involvesDatabase(new IllegalStateException("x"))).isFalse();
		assertThat(TargetFailureDiagnostics.involvesDatabase(new RuntimeException(new SQLException("x")))).isTrue();
		assertThat(TargetFailureDiagnostics.describe(null)).isEqualTo("unknown failure");
	}
}
