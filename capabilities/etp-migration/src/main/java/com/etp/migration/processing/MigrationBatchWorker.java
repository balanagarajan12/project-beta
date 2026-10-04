package com.etp.migration.processing;

import com.etp.migration.batch.BatchCompletionCounters;
import com.etp.migration.batch.MigrationBatchQueueService;
import com.etp.migration.config.WorkerIdentity;
import com.etp.migration.connector.source.MigrationSourceConnector;
import com.etp.migration.connector.source.MigrationSourceConnectorRegistry;
import com.etp.migration.connector.source.SourceBatch;
import com.etp.migration.connector.source.csv.CsvFileSourceConfiguration;
import com.etp.migration.connector.storage.ArtifactChecksumCalculator;
import com.etp.migration.connector.target.LoadableTargetRecord;
import com.etp.migration.connector.target.MigrationTargetConnector;
import com.etp.migration.connector.target.MigrationTargetConnectorRegistry;
import com.etp.migration.connector.target.MigrationTargetContext;
import com.etp.migration.connector.target.TargetInfrastructureException;
import com.etp.migration.connector.target.TargetLoadModeNotSupportedException;
import com.etp.migration.connector.target.TargetLoadResult;
import com.etp.migration.connector.target.TargetRecordError;
import com.etp.migration.connector.target.UnsupportedTargetTypeException;
import com.etp.migration.entity.LoadMode;
import com.etp.migration.execution.EntityBatchOutcome;
import com.etp.migration.execution.EntityFailureRecorder;
import com.etp.migration.execution.EntityRelationshipBatchProcessor;
import com.etp.migration.execution.ExecutionMode;
import com.etp.migration.execution.StoredEntityBatch;
import com.etp.migration.entity.MigrationBatch;
import com.etp.migration.entity.MigrationError;
import com.etp.migration.entity.MigrationMapping;
import com.etp.migration.entity.MigrationMappingRule;
import com.etp.migration.entity.MigrationRecordStatus;
import com.etp.migration.entity.MigrationRun;
import com.etp.migration.entity.MigrationSource;
import com.etp.migration.entity.MigrationStage;
import com.etp.migration.migration.MigrationSourceNotFoundException;
import com.etp.migration.repository.MigrationBatchRepository;
import com.etp.migration.repository.MigrationErrorRepository;
import com.etp.migration.repository.MigrationMappingRuleRepository;
import com.etp.migration.repository.MigrationRunRepository;
import com.etp.migration.repository.MigrationSourceRepository;
import com.etp.migration.run.InvalidStateTransitionException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MigrationBatchWorker {

	private static final Logger log = LoggerFactory.getLogger(MigrationBatchWorker.class);
	private static final int ERROR_FLUSH_SIZE = 500;

	private final TransactionTemplate transactionTemplate;
	private final MigrationBatchQueueService migrationBatchQueueService;
	private final WorkerIdentity workerIdentity;
	private final MigrationRunRepository migrationRunRepository;
	private final MigrationBatchRepository migrationBatchRepository;
	private final MigrationSourceRepository migrationSourceRepository;
	private final MigrationMappingRuleRepository mappingRuleRepository;
	private final MigrationErrorRepository migrationErrorRepository;
	private final MigrationSourceConnectorRegistry connectorRegistry;
	private final MigrationTargetConnectorRegistry targetConnectorRegistry;
	private final ArtifactChecksumCalculator checksumCalculator;
	private final MigrationRecordProcessor recordProcessor;
	private final EntityRelationshipBatchProcessor entityBatchProcessor;
	private final EntityFailureRecorder entityFailureRecorder;

	public MigrationBatchWorker(
			PlatformTransactionManager transactionManager,
			MigrationBatchQueueService migrationBatchQueueService,
			WorkerIdentity workerIdentity,
			MigrationRunRepository migrationRunRepository,
			MigrationBatchRepository migrationBatchRepository,
			MigrationSourceRepository migrationSourceRepository,
			MigrationMappingRuleRepository mappingRuleRepository,
			MigrationErrorRepository migrationErrorRepository,
			MigrationSourceConnectorRegistry connectorRegistry,
			MigrationTargetConnectorRegistry targetConnectorRegistry,
			ArtifactChecksumCalculator checksumCalculator,
			MigrationRecordProcessor recordProcessor,
			EntityRelationshipBatchProcessor entityBatchProcessor,
			EntityFailureRecorder entityFailureRecorder) {
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.migrationBatchQueueService = migrationBatchQueueService;
		this.workerIdentity = workerIdentity;
		this.migrationRunRepository = migrationRunRepository;
		this.migrationBatchRepository = migrationBatchRepository;
		this.migrationSourceRepository = migrationSourceRepository;
		this.mappingRuleRepository = mappingRuleRepository;
		this.migrationErrorRepository = migrationErrorRepository;
		this.connectorRegistry = connectorRegistry;
		this.targetConnectorRegistry = targetConnectorRegistry;
		this.checksumCalculator = checksumCalculator;
		this.recordProcessor = recordProcessor;
		this.entityBatchProcessor = entityBatchProcessor;
		this.entityFailureRecorder = entityFailureRecorder;
	}

	public Optional<ProcessBatchResponse> processNextBatch() {
		return processNextBatch(null);
	}

	public Optional<ProcessBatchResponse> processNextBatch(UUID runId) {
		Optional<MigrationBatch> claimed = transactionTemplate.execute(status -> runId == null
				? migrationBatchQueueService.claimNextBatch()
				: migrationBatchQueueService.claimNextBatchForRun(runId, workerIdentity.workerId()));
		if (claimed == null || claimed.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(processClaimedBatch(claimed.get().getId()));
	}

	public ProcessBatchResponse processBatch(Long batchId) {
		return processClaimedBatch(batchId);
	}

	public ProcessBatchResponse processNextBatchOrConflict(UUID runId) {
		return processNextBatch(runId).orElseThrow(() -> new InvalidStateTransitionException(
				"No eligible batch is available for migration run " + runId));
	}

	private ProcessBatchResponse processClaimedBatch(Long batchId) {
		transactionTemplate.executeWithoutResult(status -> migrationBatchQueueService.beginProcessing(batchId));
		try {
			BatchWork work = transactionTemplate.execute(status -> loadWork(batchId));
			verifyChecksum(work);
			BatchCompletionCounters counters = processRecords(work);
			boolean withErrors = counters.validationFailureCount() > 0
					|| counters.rejectedCount() > 0
					|| counters.loadFailureCount() > 0
					|| counters.enrichmentFailureCount() > 0;
			return transactionTemplate.execute(status -> toResponse(
					migrationBatchQueueService.completeBatch(batchId, counters, withErrors),
					counters));
		} catch (RuntimeException ex) {
			String code = errorCode(ex);
			String message = truncate(ex.getMessage() == null ? "Batch processing failed" : ex.getMessage());
			return transactionTemplate.execute(status -> {
				persistBatchError(batchId, code, message);
				return toResponse(
						migrationBatchQueueService.failBatch(batchId, message),
						new BatchCompletionCounters(0, 0, 0, 0, 0, 0, 0));
			});
		}
	}

	private BatchWork loadWork(Long batchId) {
		MigrationBatch batch = migrationBatchRepository.findById(batchId).orElseThrow();
		MigrationRun run = batch.getMigrationRun();
		run.getMigrationDefinition().getId();
		MigrationMapping mapping = run.getMigrationDefinition().getMapping();
		if (mapping == null) {
			throw new BatchProcessingException(MigrationErrorCodes.MAPPING_NOT_FOUND, "Migration mapping is not configured");
		}
		Map<String, Object> snapshot = run.getConfigurationSnapshot() == null
				? new HashMap<>()
				: new HashMap<>(run.getConfigurationSnapshot());
		Object mappedId = snapshot.get("mappingId");
		if (mappedId == null) {
			snapshot.put("mappingId", mapping.getId().toString());
			run.setConfigurationSnapshot(snapshot);
		} else if (!mapping.getId().toString().equals(mappedId.toString())) {
			throw new BatchProcessingException(
					MigrationErrorCodes.MAPPING_NOT_FOUND,
					"Mapping changed after the run started");
		}
		List<MigrationMappingRule> rules = mappingRuleRepository
				.findByMapping_IdAndEnabledTrueOrderBySequenceNoAsc(mapping.getId());
		rules.forEach(rule -> rule.getConfiguration());
		MigrationSource source = resolveSource(run);
		source.getConfiguration();
		String expectedChecksum = expectedChecksum(run);
		var definition = run.getMigrationDefinition();
		definition.getTargetType();
		definition.getTargetEntity();
		definition.getLoadMode();
		definition.getConfiguration();
		mapping.getConfiguration();
		return new BatchWork(
				batch,
				run,
				source,
				rules,
				expectedChecksum,
				definition.getTargetType(),
				definition.getTargetEntity(),
				definition.getLoadMode(),
				definition.getConfiguration() == null ? Map.of() : new HashMap<>(definition.getConfiguration()),
				mapping.getConfiguration() == null ? Map.of() : new HashMap<>(mapping.getConfiguration()),
				batch.getSourceLocator() == null ? Map.of() : new HashMap<>(batch.getSourceLocator()));
	}

	private void verifyChecksum(BatchWork work) {
		if (ExecutionMode.entityRelationship(work.mappingConfiguration())) {
			return;
		}
		if (work.expectedChecksum() == null || work.expectedChecksum().isBlank()) {
			return;
		}
		CsvFileSourceConfiguration config = CsvFileSourceConfiguration.from(work.source().getConfiguration());
		String actual = checksumCalculator.sha256(config.provider(), config.objectKey());
		if (!work.expectedChecksum().equalsIgnoreCase(actual)) {
			throw new BatchProcessingException(
					MigrationErrorCodes.SOURCE_CHANGED,
					"Source artifact checksum does not match the run snapshot");
		}
	}

	private BatchCompletionCounters processRecords(BatchWork work) {
		if (ExecutionMode.entityRelationship(work.mappingConfiguration())) {
			return processEntityBatch(work);
		}
		MigrationSourceConnector connector = connectorRegistry.require(work.source());
		int processed = 0;
		int success = 0;
		int validationFailures = 0;
		int rejected = 0;
		int loadFailures = 0;
		List<PendingError> buffer = new ArrayList<>();
		List<LoadableTargetRecord> loadable = new ArrayList<>();
		try (SourceBatch sourceBatch = connector.readBatch(work.source(), work.batch())) {
			var records = sourceBatch.records();
			if (records != null) {
				for (SourceRecord record : (Iterable<SourceRecord>) records::iterator) {
					ProcessedRecord processedRecord = recordProcessor.process(record, work.rules());
					processed++;
					if (processedRecord.status() == MigrationRecordStatus.SUCCESS) {
						loadable.add(new LoadableTargetRecord(
								processedRecord.sourceRecordNumber(),
								processedRecord.target(),
								FlatIdempotencyKey.of(
										work.run().getId(),
										work.batch().getId(),
										processedRecord.sourceRecordNumber())));
					} else if (processedRecord.status() == MigrationRecordStatus.VALIDATION_FAILED) {
						validationFailures++;
						buffer.addAll(toPending(processedRecord));
					} else {
						rejected++;
						buffer.addAll(toPending(processedRecord));
					}
					if (buffer.size() >= ERROR_FLUSH_SIZE) {
						flushErrors(work, buffer);
					}
				}
			}
		} catch (BatchProcessingException ex) {
			throw ex;
		} catch (RuntimeException ex) {
			throw new BatchProcessingException(MigrationErrorCodes.SOURCE_READ_ERROR, "Unable to read source batch", ex);
		}
		flushErrors(work, buffer);
		if (processed != work.batch().getRecordCount()) {
			throw new BatchProcessingException(
					MigrationErrorCodes.SOURCE_READ_ERROR,
					"Source batch record count does not match the planned batch");
		}
		if (!loadable.isEmpty()) {
			TargetLoadResult loadResult = loadTargets(work, loadable);
			success = loadResult.successCount();
			loadFailures = loadResult.failedCount();
			buffer.addAll(toPending(loadResult.errors()));
			flushErrors(work, buffer);
		}
		log.info(
				"Batch processing completed runId={} batchId={} batchNumber={} targetType={} targetEntity={} loadMode={} submittedCount={} successCount={} failedCount={}",
				work.run().getId(),
				work.batch().getId(),
				work.batch().getBatchNumber(),
				work.targetType(),
				work.targetEntity(),
				work.loadMode(),
				processed,
				success,
				validationFailures + rejected + loadFailures);
		return new BatchCompletionCounters(processed, success, 0, validationFailures, 0, loadFailures, rejected);
	}

	private BatchCompletionCounters processEntityBatch(BatchWork work) {
		EntityBatchOutcome outcome = entityBatchProcessor.process(new StoredEntityBatch(
				work.batch().getId(),
				work.batch().getBatchNumber(),
				work.batch().getRecordCount() == null ? 0 : work.batch().getRecordCount(),
				work.run().getId(),
				work.source().getConfiguration() == null ? Map.of() : new HashMap<>(work.source().getConfiguration()),
				work.mappingConfiguration(),
				work.rules(),
				work.sourceLocator(),
				work.targetType(),
				work.targetEntity(),
				work.loadMode(),
				work.targetConfiguration()));
		entityFailureRecorder.save(work.run().getId(), work.batch().getId(), outcome.failures());
		return outcome.counters();
	}

	private TargetLoadResult loadTargets(BatchWork work, List<LoadableTargetRecord> loadable) {
		try {
			MigrationTargetConnector targetConnector = targetConnectorRegistry.require(work.targetType());
			LoadMode loadMode = work.loadMode() == null ? LoadMode.SINGLE : work.loadMode();
			if (!targetConnector.supportsLoadMode(loadMode)) {
				throw new TargetLoadModeNotSupportedException(work.targetType(), loadMode);
			}
			MigrationTargetContext context = new MigrationTargetContext(
					work.run().getId(),
					work.batch().getId(),
					work.batch().getBatchNumber(),
					work.targetType(),
					work.targetEntity(),
					loadMode,
					work.targetConfiguration());
			return targetConnector.load(context, loadable);
		} catch (TargetLoadModeNotSupportedException ex) {
			throw new BatchProcessingException(MigrationErrorCodes.TARGET_LOAD_MODE_NOT_SUPPORTED, ex.getMessage(), ex);
		} catch (UnsupportedTargetTypeException ex) {
			throw new BatchProcessingException(MigrationErrorCodes.TARGET_TYPE_UNSUPPORTED, ex.getMessage(), ex);
		} catch (TargetInfrastructureException ex) {
			throw new BatchProcessingException(ex.errorCode(), "Target load failed", ex);
		}
	}

	private void flushErrors(BatchWork work, List<PendingError> buffer) {
		if (buffer.isEmpty()) {
			return;
		}
		List<PendingError> toSave = List.copyOf(buffer);
		buffer.clear();
		transactionTemplate.executeWithoutResult(status -> {
			MigrationRun run = migrationRunRepository.findById(work.run().getId()).orElseThrow();
			MigrationBatch batch = migrationBatchRepository.findById(work.batch().getId()).orElseThrow();
			List<MigrationError> entities = new ArrayList<>();
			for (PendingError pending : toSave) {
				MigrationError entity = new MigrationError();
				entity.setMigrationRun(run);
				entity.setMigrationBatch(batch);
				entity.setStage(pending.error().stage());
				entity.setErrorCode(pending.error().errorCode());
				entity.setErrorType(pending.error().errorType());
				entity.setSourceRecordKey(String.valueOf(pending.sourceRecordNumber()));
				entity.setFieldName(pending.error().fieldName());
				entity.setErrorMessage(truncate(pending.error().message()));
				entity.setErrorDetails(pending.error().details() == null
						? new HashMap<>()
						: new HashMap<>(pending.error().details()));
				entity.setRetryable(pending.error().retryable());
				entities.add(entity);
			}
			migrationErrorRepository.saveAll(entities);
		});
	}

	private List<PendingError> toPending(ProcessedRecord processedRecord) {
		List<PendingError> errors = new ArrayList<>();
		for (ProcessingError error : processedRecord.errors()) {
			errors.add(new PendingError(processedRecord.sourceRecordNumber(), error));
		}
		return errors;
	}

	private List<PendingError> toPending(List<TargetRecordError> errors) {
		List<PendingError> pending = new ArrayList<>();
		for (TargetRecordError error : errors) {
			pending.add(new PendingError(
					error.sourceRecordNumber(),
					new ProcessingError(
							MigrationStage.LOAD,
							error.errorCode(),
							error.errorType(),
							null,
							error.message(),
							error.httpStatus() == null ? Map.of() : Map.of("httpStatus", error.httpStatus()),
							error.retryable())));
		}
		return pending;
	}

	private void persistBatchError(Long batchId, String code, String message) {
		MigrationBatch batch = migrationBatchRepository.findById(batchId).orElseThrow();
		MigrationError entity = new MigrationError();
		entity.setMigrationRun(batch.getMigrationRun());
		entity.setMigrationBatch(batch);
		entity.setStage(code != null && code.startsWith("MIGRATION_TARGET") ? MigrationStage.LOAD : MigrationStage.EXTRACT);
		entity.setErrorCode(code);
		entity.setErrorType("BATCH_FAILED");
		entity.setErrorMessage(truncate(message));
		entity.setErrorDetails(new HashMap<>());
		entity.setRetryable(true);
		migrationErrorRepository.save(entity);
	}

	private static String errorCode(RuntimeException ex) {
		if (ex instanceof BatchProcessingException processing) {
			return processing.errorCode();
		}
		if (ex instanceof TargetInfrastructureException target) {
			return target.errorCode();
		}
		return MigrationErrorCodes.SOURCE_READ_ERROR;
	}

	private MigrationSource resolveSource(MigrationRun run) {
		UUID definitionId = run.getMigrationDefinition().getId();
		List<MigrationSource> sources = migrationSourceRepository
				.findByMigrationDefinition_IdAndEnabledTrueOrderByCreatedAtAsc(definitionId);
		if (sources.isEmpty()) {
			throw new MigrationSourceNotFoundException("No enabled migration source is configured");
		}
		return sources.get(0);
	}

	@SuppressWarnings("unchecked")
	private static String expectedChecksum(MigrationRun run) {
		Map<String, Object> snapshot = run.getSourceSnapshot();
		if (snapshot == null) {
			return null;
		}
		Object source = snapshot.get("source");
		if (source instanceof Map<?, ?> map) {
			Object checksum = map.get("checksum");
			if (checksum instanceof Map<?, ?> checksumMap && checksumMap.get("value") != null) {
				return checksumMap.get("value").toString();
			}
		}
		return null;
	}

	private static ProcessBatchResponse toResponse(MigrationBatch batch, BatchCompletionCounters counters) {
		int failed = counters.validationFailureCount() + counters.rejectedCount()
				+ counters.enrichmentFailureCount() + counters.loadFailureCount();
		return new ProcessBatchResponse(
				batch.getMigrationRun().getId(),
				batch.getId(),
				batch.getBatchNumber(),
				batch.getStatus().name(),
				counters.processedCount(),
				counters.successCount(),
				failed,
				counters.rejectedCount());
	}

	private static String truncate(String message) {
		if (message == null) {
			return "Batch processing failed";
		}
		return message.length() <= 2000 ? message : message.substring(0, 2000);
	}

	private record PendingError(long sourceRecordNumber, ProcessingError error) {
	}

	private record BatchWork(
			MigrationBatch batch,
			MigrationRun run,
			MigrationSource source,
			List<MigrationMappingRule> rules,
			String expectedChecksum,
			String targetType,
			String targetEntity,
			LoadMode loadMode,
			Map<String, Object> targetConfiguration,
			Map<String, Object> mappingConfiguration,
			Map<String, Object> sourceLocator
	) {
	}
}
