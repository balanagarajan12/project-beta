package com.etp.migration.run;

import com.etp.migration.processing.MigrationBatchWorker;
import com.etp.migration.processing.SourceInspectionService;
import com.etp.migration.run.dto.CreateMigrationRunRequest;
import com.etp.migration.run.dto.MigrationRunResponse;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class MigrationExecutionService {

	private final MigrationRunService migrationRunService;
	private final SourceInspectionService sourceInspectionService;
	private final MigrationBatchWorker migrationBatchWorker;

	public MigrationExecutionService(
			MigrationRunService migrationRunService,
			SourceInspectionService sourceInspectionService,
			MigrationBatchWorker migrationBatchWorker) {
		this.migrationRunService = migrationRunService;
		this.sourceInspectionService = sourceInspectionService;
		this.migrationBatchWorker = migrationBatchWorker;
	}

	public MigrationRunResponse execute(UUID migrationId, CreateMigrationRunRequest request) {
		CreateMigrationRunRequest createRequest = request == null
				? new CreateMigrationRunRequest(null, null, null)
				: request;
		MigrationRunResponse created = migrationRunService.create(migrationId, createRequest);
		UUID runId = created.id();
		sourceInspectionService.prepare(runId);
		while (migrationBatchWorker.processNextBatch(runId).isPresent()) {
			// Continue until no eligible batch remains.
		}
		return migrationRunService.getById(runId);
	}
}
