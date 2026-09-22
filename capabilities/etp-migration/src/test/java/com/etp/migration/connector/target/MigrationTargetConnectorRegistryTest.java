package com.etp.migration.connector.target;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.etp.migration.connector.target.postgresql.PostgreSqlTargetConnector;
import com.etp.migration.connector.target.postgresql.PostgreSqlTargetProperties;
import com.etp.migration.connector.target.TargetLoadModeNotSupportedException;
import com.etp.migration.connector.target.uam.UamAccessTokenProvider;
import com.etp.migration.connector.target.uam.UamTargetConnector;
import com.etp.migration.connector.target.uam.UamTargetProperties;
import com.etp.migration.entity.LoadMode;
import com.etp.migration.processing.MigrationErrorCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MigrationTargetConnectorRegistryTest {

	@Test
	void resolvesUamAndRejectsUnknownType() {
		UamTargetConnector uam = new UamTargetConnector(
				new UamTargetProperties("http://localhost", "/api/v1/{entity}", Duration.ofSeconds(1), Duration.ofSeconds(1), "", "", "", ""),
				new UamAccessTokenProvider(new UamTargetProperties("http://localhost", "/api/v1/{entity}", Duration.ofSeconds(1), Duration.ofSeconds(1), "", "", "", "")),
				new ObjectMapper());
		MigrationTargetConnectorRegistry registry = new MigrationTargetConnectorRegistry(List.of(uam));
		assertThat(registry.require("UAM")).isSameAs(uam);
		assertThatThrownBy(() -> registry.require("CRM")).isInstanceOf(UnsupportedTargetTypeException.class);
		assertThat(uam.supportsLoadMode(LoadMode.SINGLE)).isTrue();
		assertThat(uam.supportsLoadMode(LoadMode.BATCH)).isFalse();
		assertThat(uam.supportsLoadMode(LoadMode.BULK)).isFalse();
	}

	@Test
	void resolvesPostgresqlBulkAndRejectsSingle() {
		PostgreSqlTargetConnector postgres = new PostgreSqlTargetConnector(
				new PostgreSqlTargetProperties("", "", "", "migration_staging", 1),
				null,
				new ObjectMapper());
		MigrationTargetConnectorRegistry registry = new MigrationTargetConnectorRegistry(List.of(postgres));
		assertThat(registry.require("POSTGRESQL")).isSameAs(postgres);
		assertThat(postgres.supportsLoadMode(LoadMode.BULK)).isTrue();
		assertThat(postgres.supportsLoadMode(LoadMode.SINGLE)).isFalse();
		assertThat(postgres.supportsLoadMode(LoadMode.BATCH)).isFalse();
		assertThatThrownBy(() -> postgres.load(
						new MigrationTargetContext(UUID.randomUUID(), 1L, 1, "POSTGRESQL", "address", LoadMode.SINGLE, Map.of()),
						List.of()))
				.isInstanceOf(TargetLoadModeNotSupportedException.class);
	}

	@Test
	void targetContextCarriesGenericFields() {
		UUID runId = UUID.randomUUID();
		MigrationTargetContext context = new MigrationTargetContext(
				runId, 9L, 1, "UAM", "address", LoadMode.SINGLE, Map.of("target", Map.of("entity", "address")));
		assertThat(context.migrationRunId()).isEqualTo(runId);
		assertThat(context.targetType()).isEqualTo("UAM");
		assertThat(context.loadMode()).isEqualTo(LoadMode.SINGLE);
	}

	@Test
	void loadResultAggregatesCounts() {
		TargetLoadResult result = new TargetLoadResult(
				2,
				1,
				1,
				List.of(new TargetReference(1L, "t-1", com.etp.migration.entity.MigrationRecordStatus.SUCCESS)),
				List.of(new TargetRecordError(2L, MigrationErrorCodes.TARGET_4XX, "LOAD_FAILED", "failed", false, 400)));
		assertThat(result.submittedCount()).isEqualTo(2);
		assertThat(result.successCount()).isEqualTo(1);
		assertThat(result.failedCount()).isEqualTo(1);
		assertThat(result.targetReferences().get(0).targetId()).isEqualTo("t-1");
		assertThat(result.errors().get(0).retryable()).isFalse();
	}
}
