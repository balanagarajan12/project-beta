package com.etp.migration.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.etp.migration.entity.MigrationConfigStatus;
import com.etp.migration.entity.MigrationRunStatus;
import com.etp.migration.migration.MigrationDefinitionNotFoundException;
import com.etp.migration.run.dto.CreateMigrationRunRequest;
import com.etp.migration.run.dto.MigrationRunResponse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class MigrationExecutionServiceTest {

	private static final UUID DEFINITION_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
	private static final UUID RUN_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

	@Mock
	private MigrationRunService migrationRunService;
	@Mock
	private MigrationExecutionCoordinator coordinator;

	@Mock
	private ExecutionControlService controls;

	private MigrationExecutionService service;

	@BeforeEach
	void setUp() {
		service = new MigrationExecutionService(migrationRunService, coordinator, controls);
	}

	@Test
	void facadeHasNoTransactionalAnnotation() throws Exception {
		assertThat(MigrationExecutionService.class.getAnnotation(Transactional.class)).isNull();
		assertThat(MigrationExecutionService.class
				.getMethod("execute", UUID.class, CreateMigrationRunRequest.class)
				.getAnnotation(Transactional.class)).isNull();
	}

	@Test
	void executeReturnsTheQueuedSnapshotAndSchedulesAfterTheRunIsMarked() {
		when(migrationRunService.create(eq(DEFINITION_ID), any())).thenReturn(run(MigrationRunStatus.QUEUED));

		MigrationRunResponse response = service.execute(DEFINITION_ID, null);

		assertThat(response.status()).isEqualTo(MigrationRunStatus.QUEUED);
		assertThat(response.id()).isEqualTo(RUN_ID);
		verify(migrationRunService).requestBackgroundExecution(RUN_ID);
		verify(coordinator).submit(RUN_ID);
		verify(migrationRunService, never()).getById(any());
	}

	@Test
	void terminalIdempotentRunIsNotScheduledAgain() {
		when(migrationRunService.create(eq(DEFINITION_ID), any()))
				.thenReturn(run(MigrationRunStatus.COMPLETED));

		assertThat(service.execute(DEFINITION_ID, new CreateMigrationRunRequest("same", null, null)).status())
				.isEqualTo(MigrationRunStatus.COMPLETED);
		verify(migrationRunService, never()).requestBackgroundExecution(any());
		verify(coordinator, never()).submit(any());
	}

	@Test
	void definitionNotActiveStopsBeforeScheduling() {
		when(migrationRunService.create(eq(DEFINITION_ID), any()))
				.thenThrow(new DefinitionNotActiveException(DEFINITION_ID, MigrationConfigStatus.DRAFT));

		assertThatThrownBy(() -> service.execute(DEFINITION_ID, null))
				.isInstanceOf(DefinitionNotActiveException.class);
		verify(coordinator, never()).submit(any());
		verify(migrationRunService, never()).requestBackgroundExecution(any());
	}

	@Test
	void missingDefinitionStopsBeforeScheduling() {
		when(migrationRunService.create(eq(DEFINITION_ID), any()))
				.thenThrow(new MigrationDefinitionNotFoundException(DEFINITION_ID));

		assertThatThrownBy(() -> service.execute(DEFINITION_ID, null))
				.isInstanceOf(MigrationDefinitionNotFoundException.class);
		verify(coordinator, never()).submit(any());
	}

	private static MigrationRunResponse run(MigrationRunStatus status) {
		Instant now = Instant.parse("2026-09-21T00:00:00Z");
		return new MigrationRunResponse(
				RUN_ID, DEFINITION_ID, 1L, null, status, now, now,
				0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
				0, 0, 0,
				"system", now, now);
	}
}
