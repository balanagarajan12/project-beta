package com.etp.migration.run;

import com.etp.migration.entity.MigrationBatchStatus;
import com.etp.migration.entity.MigrationRun;
import com.etp.migration.entity.MigrationRunStatus;
import com.etp.migration.repository.MigrationBatchRepository;
import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class RunCompletionEvaluator {

	private final MigrationBatchRepository migrationBatchRepository;

	public RunCompletionEvaluator(MigrationBatchRepository migrationBatchRepository) {
		this.migrationBatchRepository = migrationBatchRepository;
	}

	public void evaluate(MigrationRun run, Instant now) {
		UUID runId = run.getId();
		long inFlight = migrationBatchRepository.countByMigrationRun_IdAndStatusIn(
				runId,
				EnumSet.of(
						MigrationBatchStatus.PENDING,
						MigrationBatchStatus.RETRY_PENDING,
						MigrationBatchStatus.CLAIMED,
						MigrationBatchStatus.PROCESSING));
		if (inFlight > 0) {
			return;
		}
		if (run.getStatus() == MigrationRunStatus.CANCEL_REQUESTED) {
			MigrationRunTransitions.require(MigrationRunStatus.CANCEL_REQUESTED, MigrationRunStatus.CANCELLED);
			run.setStatus(MigrationRunStatus.CANCELLED);
			run.setCompletedAt(now);
			run.setUpdatedAt(now);
			return;
		}
		if (run.getStatus() != MigrationRunStatus.RUNNING && run.getStatus() != MigrationRunStatus.QUEUED) {
			return;
		}
		long failed = migrationBatchRepository.countByMigrationRun_IdAndStatusIn(
				runId, EnumSet.of(MigrationBatchStatus.FAILED));
		long completedWithErrors = migrationBatchRepository.countByMigrationRun_IdAndStatusIn(
				runId, EnumSet.of(MigrationBatchStatus.COMPLETED_WITH_ERRORS));
		MigrationRunStatus target;
		if (failed > 0) {
			target = MigrationRunStatus.FAILED;
		} else if (completedWithErrors > 0) {
			target = MigrationRunStatus.COMPLETED_WITH_ERRORS;
		} else {
			target = MigrationRunStatus.COMPLETED;
		}
		if (run.getStatus() == MigrationRunStatus.QUEUED) {
			MigrationRunTransitions.require(MigrationRunStatus.QUEUED, MigrationRunStatus.RUNNING);
			run.setStatus(MigrationRunStatus.RUNNING);
			if (run.getStartedAt() == null) {
				run.setStartedAt(now);
			}
		}
		MigrationRunTransitions.require(run.getStatus(), target);
		run.setStatus(target);
		run.setCompletedAt(now);
		run.setUpdatedAt(now);
	}
}
