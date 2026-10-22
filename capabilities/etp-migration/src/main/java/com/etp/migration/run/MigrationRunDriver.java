package com.etp.migration.run;

import com.etp.migration.config.ShutdownSignal;
import com.etp.migration.processing.MigrationBatchWorker;
import com.etp.migration.processing.MigrationLogContext;
import com.etp.migration.processing.SourceInspectionService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Drives one accepted run: prepare, then the existing batch worker, then finalize.
 * The HTTP thread only schedules this work. A lost lease stops the driver without
 * finalizing a run another worker owns. Shutdown releases the lease and leaves the
 * run for recovery; it does not mark the run failed.
 */
@Component
public class MigrationRunDriver {

	private static final Logger log = LoggerFactory.getLogger(MigrationRunDriver.class);

	private final SourceInspectionService sourceInspectionService;
	private final MigrationBatchWorker migrationBatchWorker;
	private final RunExecutionLifecycle runExecutionLifecycle;
	private final MigrationExecutionClaim claim;
	private final MigrationExecutionProgress progress;
	private final ShutdownSignal shutdownSignal;
	private final ExecutionControlService controls;

	public MigrationRunDriver(
			SourceInspectionService sourceInspectionService,
			MigrationBatchWorker migrationBatchWorker,
			RunExecutionLifecycle runExecutionLifecycle,
			MigrationExecutionClaim claim,
			MigrationExecutionProgress progress,
			ShutdownSignal shutdownSignal,
			ExecutionControlService controls) {
		this.sourceInspectionService = sourceInspectionService;
		this.migrationBatchWorker = migrationBatchWorker;
		this.runExecutionLifecycle = runExecutionLifecycle;
		this.claim = claim;
		this.progress = progress;
		this.shutdownSignal = shutdownSignal;
		this.controls = controls;
	}

	public void drive(UUID runId) {
		if (shutdownSignal.isShuttingDown()) {
			log.info("Run claim skipped because the application is shutting down runId={}", runId);
			return;
		}
		if (!claim.tryClaim(runId)) {
			log.info("Run claim skipped runId={}", runId);
			return;
		}
		try (MigrationLogContext ignored = MigrationLogContext.forRun(runId)) {
			log.info("Run claimed runId={}", runId);
			boolean prepared = false;
			try {
				runExecutionLifecycle.beginExecution(runId);
				log.info("Preparation started runId={}", runId);
				sourceInspectionService.prepare(runId);
				MigrationExecutionClaim.Ownership ownership = claim.keep(runId);
				if (ownership == MigrationExecutionClaim.Ownership.LOST) {
					log.warn("Run lease lost after preparation runId={}", runId);
					return;
				}
				if (ownership == MigrationExecutionClaim.Ownership.FINISHED) {
					log.info("Run execution finished runId={}", runId);
					return;
				}
				prepared = true;
				progress.mark(runId);
				log.info("Preparation completed runId={}", runId);
				controls.markRunRunning(runId);
				log.info("Batch processing started runId={}", runId);
				while (!shutdownSignal.isShuttingDown()) {
					if (controls.shouldPauseRun(runId)) {
						controls.markRunPaused(runId);
						log.info("Run paused at a batch boundary runId={}", runId);
						return;
					}
					ownership = claim.keep(runId);
					if (ownership != MigrationExecutionClaim.Ownership.ACTIVE) {
						break;
					}
					if (migrationBatchWorker.processNextBatch(runId).isEmpty()) {
						break;
					}
					progress.mark(runId);
				}
				if (shutdownSignal.isShuttingDown()) {
					log.warn("Stopped claiming batches because the application is shutting down runId={}; "
							+ "recovery will resume eligible batches", runId);
					return;
				}
				if (ownership == MigrationExecutionClaim.Ownership.LOST) {
					log.warn("Run lease lost during batch processing runId={}", runId);
					return;
				}
				if (ownership == MigrationExecutionClaim.Ownership.FINISHED) {
					log.info("Run execution finished runId={}", runId);
					return;
				}
				log.info("Batch processing completed runId={}", runId);
				runExecutionLifecycle.finalizeIfIdle(runId);
				log.info("Run execution finished runId={}", runId);
			} catch (RuntimeException ex) {
				log.warn("Run execution failed runId={} exceptionType={}", runId, ex.getClass().getName());
				if (prepared && claim.keep(runId) == MigrationExecutionClaim.Ownership.ACTIVE) {
					finalizeQuietly(runId);
				}
			} finally {
				// Release matches this worker id, so a run another worker has claimed is left alone.
				claim.release(runId);
			}
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
