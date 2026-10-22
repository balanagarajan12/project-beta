package com.etp.migration.run;

import com.etp.migration.processing.MigrationLogContext;
import com.etp.migration.run.dto.CreateMigrationRunRequest;
import com.etp.migration.run.dto.MigrationRunResponse;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Accepts an execution request. Preparation and batch processing run on
 * {@link MigrationExecutionCoordinator} after the run row has committed.
 */
@Service
public class MigrationExecutionService {

	private static final Logger log = LoggerFactory.getLogger(MigrationExecutionService.class);

	private final MigrationRunService migrationRunService;
	private final MigrationExecutionCoordinator coordinator;
	private final ExecutionControlService controls;

	public MigrationExecutionService(
			MigrationRunService migrationRunService,
			MigrationExecutionCoordinator coordinator,
			ExecutionControlService controls) {
		this.migrationRunService = migrationRunService;
		this.coordinator = coordinator;
		this.controls = controls;
	}

	public MigrationRunResponse execute(UUID migrationId, CreateMigrationRunRequest request) {
		CreateMigrationRunRequest createRequest = request == null
				? new CreateMigrationRunRequest(null, null, null)
				: request;
		MigrationRunResponse created = migrationRunService.create(migrationId, createRequest);
		if (MigrationRunTransitions.isTerminal(created.status())) {
			log.info("Run already finished runId={} status={}", created.id(), created.status());
			return created;
		}
		controls.requireRunRunnable(created.id());
		migrationRunService.requestBackgroundExecution(created.id());
		try (MigrationLogContext ignored = MigrationLogContext.forRun(created.id())) {
			log.info("Run accepted runId={} status={}", created.id(), created.status());
		}
		coordinator.submit(created.id());
		return created;
	}
}
