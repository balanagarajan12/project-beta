package com.etp.migration.run;

import com.etp.migration.config.ShutdownSignal;
import com.etp.migration.processing.MigrationBatchWorker;
import com.etp.migration.processing.MigrationLogContext;
import com.etp.migration.processing.SourceInspectionService;
import com.etp.migration.run.dto.CreateMigrationRunRequest;
import com.etp.migration.run.dto.MigrationRunResponse;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class MigrationExecutionService {

	private static final Logger log = LoggerFactory.getLogger(MigrationExecutionService.class);

	private final MigrationRunService migrationRunService;
	private final SourceInspectionService sourceInspectionService;
	private final MigrationBatchWorker migrationBatchWorker;
	private final RunExecutionLifecycle runExecutionLifecycle;
	private final ShutdownSignal shutdownSignal;

	public MigrationExecutionService(
			MigrationRunService migrationRunService,
			SourceInspectionService sourceInspectionService,
			MigrationBatchWorker migrationBatchWorker,
			RunExecutionLifecycle runExecutionLifecycle,
			ShutdownSignal shutdownSignal) {
		this.migrationRunService = migrationRunService;
		this.sourceInspectionService = sourceInspectionService;
		this.migrationBatchWorker = migrationBatchWorker;
		this.runExecutionLifecycle = runExecutionLifecycle;
		this.shutdownSignal = shutdownSignal;
	}

	public MigrationRunResponse execute(UUID migrationId, CreateMigrationRunRequest request) {
		CreateMigrationRunRequest createRequest = request == null
				? new CreateMigrationRunRequest(null, null, null)
				: request;
		MigrationRunResponse created = migrationRunService.create(migrationId, createRequest);
		UUID runId = created.id();
		try (MigrationLogContext ignored = MigrationLogContext.forRun(runId)) {
			sourceInspectionService.prepare(runId);
			runExecutionLifecycle.beginExecution(runId);
			boolean finished = false;
			try {
				while (!shutdownSignal.isShuttingDown() && migrationBatchWorker.processNextBatch(runId).isPresent()) {
					// Continue until no eligible batch remains.
				}
				finished = true;
			} finally {
				if (!finished) {
					finalizeQuietly(runId);
				}
			}
			if (shutdownSignal.isShuttingDown()) {
				log.warn("Stopped claiming batches because the application is shutting down migrationRunId={}; "
						+ "continue with POST /api/v1/migration-runs/{}/process-next-batch", runId, runId);
			}
			runExecutionLifecycle.finalizeIfIdle(runId);
			return migrationRunService.getById(runId);
		}
	}

	private void finalizeQuietly(UUID runId) {
		try {
			runExecutionLifecycle.finalizeIfIdle(runId);
		} catch (RuntimeException ex) {
			log.warn("Run finalization failed runId={} exceptionType={}", runId, ex.getClass().getName());
		}
	}
}
