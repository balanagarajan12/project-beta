package com.etp.migration.run;

import com.etp.migration.entity.MigrationRun;
import com.etp.migration.entity.MigrationRunStatus;
import com.etp.migration.repository.MigrationRunRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves a run from QUEUED to RUNNING when its own execution starts, then recalculates the
 * terminal status once no batch is still in flight. A created run that has not been executed
 * is not touched.
 */
@Component
public class RunExecutionLifecycle {

	private final MigrationRunRepository migrationRunRepository;
	private final RunCompletionEvaluator runCompletionEvaluator;

	public RunExecutionLifecycle(
			MigrationRunRepository migrationRunRepository,
			RunCompletionEvaluator runCompletionEvaluator) {
		this.migrationRunRepository = migrationRunRepository;
		this.runCompletionEvaluator = runCompletionEvaluator;
	}

	@Transactional
	public void beginExecution(UUID runId) {
		MigrationRun run = migrationRunRepository.findByIdForUpdate(runId)
				.orElseThrow(() -> new MigrationRunNotFoundException(runId));
		if (run.getStatus() != MigrationRunStatus.QUEUED) {
			return;
		}
		Instant now = Instant.now();
		MigrationRunTransitions.require(MigrationRunStatus.QUEUED, MigrationRunStatus.RUNNING);
		run.setStatus(MigrationRunStatus.RUNNING);
		if (run.getStartedAt() == null) {
			run.setStartedAt(now);
		}
		run.setUpdatedAt(now);
	}

	@Transactional
	public void finalizeIfIdle(UUID runId) {
		MigrationRun run = migrationRunRepository.findByIdForUpdate(runId)
				.orElseThrow(() -> new MigrationRunNotFoundException(runId));
		runCompletionEvaluator.evaluate(run, Instant.now());
	}
}
