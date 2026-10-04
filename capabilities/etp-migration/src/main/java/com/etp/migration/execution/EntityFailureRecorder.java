package com.etp.migration.execution;

import com.etp.migration.entity.MigrationBatch;
import com.etp.migration.entity.MigrationError;
import com.etp.migration.entity.MigrationRun;
import com.etp.migration.repository.MigrationBatchRepository;
import com.etp.migration.repository.MigrationErrorRepository;
import com.etp.migration.repository.MigrationRunRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class EntityFailureRecorder {

	private final TransactionTemplate transactionTemplate;
	private final MigrationRunRepository migrationRunRepository;
	private final MigrationBatchRepository migrationBatchRepository;
	private final MigrationErrorRepository migrationErrorRepository;

	public EntityFailureRecorder(
			PlatformTransactionManager transactionManager,
			MigrationRunRepository migrationRunRepository,
			MigrationBatchRepository migrationBatchRepository,
			MigrationErrorRepository migrationErrorRepository) {
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.migrationRunRepository = migrationRunRepository;
		this.migrationBatchRepository = migrationBatchRepository;
		this.migrationErrorRepository = migrationErrorRepository;
	}

	public void save(java.util.UUID runId, Long batchId, List<EntityItemFailure> failures) {
		if (failures == null || failures.isEmpty()) {
			return;
		}
		List<EntityItemFailure> copy = List.copyOf(failures);
		transactionTemplate.executeWithoutResult(status -> {
			MigrationRun run = migrationRunRepository.findById(runId).orElseThrow();
			MigrationBatch batch = batchId == null ? null : migrationBatchRepository.findById(batchId).orElse(null);
			List<MigrationError> entities = new ArrayList<>();
			for (EntityItemFailure failure : copy) {
				MigrationError entity = new MigrationError();
				entity.setMigrationRun(run);
				entity.setMigrationBatch(batch);
				entity.setStage(failure.stage());
				entity.setErrorCode(limit(failure.errorCode(), 100));
				entity.setErrorType(limit(failure.errorType(), 100));
				entity.setSourceRecordKey(sourceKey(failure));
				entity.setTargetEntityKey(limit(text(failure.details().get("targetEntity")), 500));
				entity.setFieldName(limit(failure.fieldName(), 500));
				entity.setErrorMessage(limit(failure.message() == null ? "Entity processing failed" : failure.message(), 2000));
				entity.setErrorDetails(details(failure));
				entity.setRetryable(failure.retryable());
				entities.add(entity);
			}
			migrationErrorRepository.saveAll(entities);
		});
	}

	private static Map<String, Object> details(EntityItemFailure failure) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("entityName", failure.entityName() == null ? "" : failure.entityName());
		details.put("sourceIdentity", failure.sourceIdentity() == null ? "" : failure.sourceIdentity());
		details.put("stage", failure.stage().name());
		if (failure.details() != null) {
			details.putAll(failure.details());
		}
		return new HashMap<>(details);
	}

	private static String sourceKey(EntityItemFailure failure) {
		if (failure.sourceIdentity() != null && !failure.sourceIdentity().isBlank()) {
			return limit(failure.sourceIdentity(), 500);
		}
		return String.valueOf(failure.sourceRecordNumber());
	}

	private static String text(Object value) {
		return value == null ? null : value.toString();
	}

	private static String limit(String value, int max) {
		if (value == null) {
			return null;
		}
		return value.length() <= max ? value : value.substring(0, max);
	}
}
