package com.etp.migration.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.etp.migration.connector.target.TargetVerification;
import com.etp.migration.entity.MigrationRun;
import com.etp.migration.entity.MigrationRunStatus;
import com.etp.migration.entity.ReconciliationStatus;
import com.etp.migration.processing.MigrationErrorCodes;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReconciliationEngineTest {

	private final ReconciliationEngine engine = new ReconciliationEngine();

	@Test
	void postgresqlStagingMatchIsPassedWithStagingScope() {
		ReconciliationContext context = context(
				MigrationRunStatus.COMPLETED,
				2, 2, 2, 0, 0, 0,
				new TargetVerification(true, 2L, "STAGING", false, "staging only", Map.of(
						"targetSchema", "migration_staging",
						"targetTable", "address_load",
						"mergeStrategy", "NONE")));
		ReconciliationComputation count = engine.count(context);
		ReconciliationComputation target = engine.target(context);
		ReconciliationComputation checksum = engine.checksum(context);
		ReconciliationComputation full = engine.full(context, count, checksum, target);
		assertThat(count.status()).isEqualTo(ReconciliationStatus.PASSED);
		assertThat(count.details().get("verificationScope")).isEqualTo("STAGING");
		assertThat(count.details().get("businessTargetVerified")).isEqualTo(false);
		assertThat(target.status()).isEqualTo(ReconciliationStatus.PASSED);
		assertThat(checksum.status()).isEqualTo(ReconciliationStatus.PASSED_WITH_WARNINGS);
		assertThat(checksum.details().get("comparisonPerformed")).isEqualTo(false);
		assertThat(checksum.details().get("sourceChecksum")).isEqualTo("abc");
		assertThat(full.status()).isEqualTo(ReconciliationStatus.PASSED_WITH_WARNINGS);
	}

	@Test
	void stagingCountMismatchFailsWithTargetCountMismatch() {
		ReconciliationContext context = context(
				MigrationRunStatus.COMPLETED,
				2, 2, 2, 0, 0, 0,
				new TargetVerification(true, 1L, "STAGING", false, "staging only", Map.of()));
		ReconciliationComputation count = engine.count(context);
		assertThat(count.status()).isEqualTo(ReconciliationStatus.FAILED);
		assertThat(count.details().get("errorCode")).isEqualTo(MigrationErrorCodes.TARGET_COUNT_MISMATCH);
		assertThat(engine.target(context).status()).isEqualTo(ReconciliationStatus.FAILED);
	}

	@Test
	void processingErrorsRemainConsistentWithWarnings() {
		ReconciliationContext context = context(
				MigrationRunStatus.COMPLETED_WITH_ERRORS,
				2, 2, 1, 1, 0, 0,
				new TargetVerification(true, 1L, "STAGING", false, "staging only", Map.of()));
		ReconciliationComputation count = engine.count(context);
		assertThat(count.status()).isEqualTo(ReconciliationStatus.PASSED_WITH_WARNINGS);
		assertThat(count.details().get("internallyConsistent")).isEqualTo(true);
	}

	@Test
	void unavailableTargetIsWarningNotFailure() {
		ReconciliationContext context = context(
				MigrationRunStatus.COMPLETED,
				1, 1, 1, 0, 0, 0,
				TargetVerification.unavailable("BUSINESS_TARGET", "UAM target count verification is not available in the current target contract"));
		ReconciliationComputation target = engine.target(context);
		assertThat(target.status()).isEqualTo(ReconciliationStatus.PASSED_WITH_WARNINGS);
		assertThat(target.details().get("targetVerificationAvailable")).isEqualTo(false);
		assertThat(target.details().get("errorCode")).isEqualTo(MigrationErrorCodes.TARGET_VERIFICATION_UNAVAILABLE);
		assertThat(engine.count(context).status()).isEqualTo(ReconciliationStatus.PASSED_WITH_WARNINGS);
	}

	@Test
	void businessTargetMatchPassesWithoutUsingSourceCount() {
		ReconciliationContext context = context(
				MigrationRunStatus.COMPLETED,
				8, 8, 7, 1, 0, 0,
				new TargetVerification(true, 7L, "BUSINESS_TARGET", true, "matched", Map.of(
						"expectedCount", 7L,
						"valueMismatch", false,
						"duplicateIdentity", false)));
		ReconciliationComputation target = engine.target(context);
		assertThat(target.status()).isEqualTo(ReconciliationStatus.PASSED);
		assertThat(target.sourceCount()).isEqualTo(7L);
		assertThat(target.details().get("verificationScope")).isEqualTo("BUSINESS_TARGET");
		assertThat(target.details().get("businessTargetVerified")).isEqualTo(true);
		assertThat(engine.count(context).status()).isEqualTo(ReconciliationStatus.PASSED_WITH_WARNINGS);
	}

	@Test
	void businessTargetValueMismatchFails() {
		ReconciliationContext context = context(
				MigrationRunStatus.COMPLETED,
				5, 5, 5, 0, 0, 0,
				new TargetVerification(true, 5L, "BUSINESS_TARGET", false, "mismatch", Map.of(
						"expectedCount", 5L,
						"valueMismatch", true,
						"duplicateIdentity", false)));
		ReconciliationComputation target = engine.target(context);
		assertThat(target.status()).isEqualTo(ReconciliationStatus.FAILED);
		assertThat(target.details().get("errorCode")).isEqualTo(MigrationErrorCodes.TARGET_VALUE_MISMATCH);
		assertThat(engine.full(context, engine.count(context), engine.checksum(context), target).status())
				.isEqualTo(ReconciliationStatus.FAILED);
	}

	@Test
	void missingIdentityConfigurationWarns() {
		ReconciliationContext context = context(
				MigrationRunStatus.COMPLETED,
				2, 2, 2, 0, 0, 0,
				new TargetVerification(false, null, "BUSINESS_TARGET", false, "no keys", Map.of(
						"errorCode", MigrationErrorCodes.TARGET_IDENTITY_NOT_CONFIGURED)));
		ReconciliationComputation target = engine.target(context);
		assertThat(target.status()).isEqualTo(ReconciliationStatus.PASSED_WITH_WARNINGS);
		assertThat(target.details().get("errorCode")).isEqualTo(MigrationErrorCodes.TARGET_IDENTITY_NOT_CONFIGURED);
		assertThat(target.details().get("businessTargetVerified")).isEqualTo(false);
	}

	private static ReconciliationContext context(
			MigrationRunStatus runStatus,
			long source,
			long processed,
			long success,
			long rejected,
			long duplicate,
			long failure,
			TargetVerification verification) {
		MigrationRun run = new MigrationRun();
		run.setStatus(runStatus);
		return new ReconciliationContext(
				run,
				source,
				processed,
				success,
				rejected,
				duplicate,
				failure,
				"abc",
				"SHA-256",
				verification);
	}
}
