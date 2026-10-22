package com.etp.migration.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.etp.migration.batch.BatchPlanner;
import com.etp.migration.entity.MigrationError;
import com.etp.migration.entity.MigrationRun;
import com.etp.migration.entity.MigrationStage;
import com.etp.migration.repository.MigrationBatchRepository;
import com.etp.migration.repository.MigrationDefinitionRepository;
import com.etp.migration.repository.MigrationErrorRepository;
import com.etp.migration.repository.MigrationPromotionRepository;
import com.etp.migration.repository.MigrationRunRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class MigrationErrorRedactionTest {

	@Mock
	private MigrationDefinitionRepository definitions;

	@Mock
	private MigrationRunRepository runs;

	@Mock
	private MigrationBatchRepository batches;

	@Mock
	private MigrationPromotionRepository promotions;

	@Mock
	private MigrationErrorRepository errors;

	@Mock
	private BatchPlanner planner;

	@Test
	void errorApiOmitsStackTracesAndSecrets() {
		UUID runId = UUID.randomUUID();
		MigrationRun run = new MigrationRun();
		run.setId(runId);
		MigrationError error = new MigrationError();
		error.setMigrationRun(run);
		error.setStage(MigrationStage.PROMOTE);
		error.setErrorCode("MIGRATION_PROMOTION_BATCH_FAILED");
		error.setErrorType("PROMOTION_BATCH");
		error.setSourceRecordKey("password=hunter2");
		error.setFieldName("city");
		error.setErrorMessage("password=hunter2");
		java.util.Map<String, Object> storedDetails = new java.util.LinkedHashMap<>();
		storedDetails.put("sqlState", "23503");
		storedDetails.put("category", "BATCH");
		storedDetails.put("stackTrace", "java.lang.NullPointerException\n\tat hidden.Frame.line(File.java:1)");
		storedDetails.put("message", "key (id)=(secret) already exists");
		storedDetails.put("context", java.util.Map.of("note", "bearer token"));
		storedDetails.put("rows", java.util.List.of(java.util.Map.of("value", "api_key=hidden")));
		error.setErrorDetails(storedDetails);
		error.setRetryable(true);
		when(runs.findById(runId)).thenReturn(Optional.of(run));
		when(errors.findByMigrationRun_Id(eq(runId), any(Pageable.class)))
				.thenReturn(new PageImpl<>(java.util.List.of(error)));
		MigrationRunService service = new MigrationRunService(
				definitions, runs, batches, promotions, errors, planner);

		var page = service.listErrors(runId, 0, 20);

		assertThat(page.content()).singleElement().satisfies(response -> {
			assertThat(response.errorMessage()).isEqualTo("Error details were redacted");
			assertThat(response.sourceRecordKey()).isEqualTo("redacted");
			assertThat(response.fieldName()).isEqualTo("city");
			assertThat(response.stage()).isEqualTo("PROMOTE");
			assertThat(response.errorCode()).isEqualTo("MIGRATION_PROMOTION_BATCH_FAILED");
			assertThat(response.errorDetails())
					.containsEntry("sqlState", "23503")
					.containsEntry("category", "BATCH")
					.doesNotContainKeys("stackTrace", "message");
			assertThat(response.errorDetails().get("context")).isEqualTo(Map.of());
			assertThat(response.errorDetails().get("rows")).isEqualTo(java.util.List.of(Map.of()));
			assertThat(response.errorDetails().toString()).doesNotContain("hunter2", "secret", "bearer", "api_key");
		});
		assertThat(error.getSourceRecordKey()).isEqualTo("password=hunter2");
		assertThat(error.getErrorDetails()).containsKey("stackTrace");
		assertThat(error.getErrorDetails().get("message")).isEqualTo("key (id)=(secret) already exists");
	}
}
