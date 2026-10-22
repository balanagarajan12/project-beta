package com.etp.migration.processing;

import com.etp.migration.batch.BatchPlanner;
import com.etp.migration.connector.source.InvalidSourceConfigurationException;
import com.etp.migration.connector.source.csv.SourceByteProgress;
import com.etp.migration.entity.MigrationMappingRule;
import com.etp.migration.execution.EntityBatchLocator;
import com.etp.migration.execution.EntityFailureRecorder;
import com.etp.migration.execution.EntityItemFailure;
import com.etp.migration.execution.EntityRelationshipPlanSource;
import com.etp.migration.execution.ExecutionMode;
import com.etp.migration.mapping.EntityRelationshipConfiguration;
import com.etp.migration.planning.ExecutionBatchPlan;
import com.etp.migration.planning.ExecutionWorkItem;
import com.etp.migration.repository.MigrationMappingRuleRepository;
import com.etp.migration.connector.source.MigrationSourceConnector;
import com.etp.migration.connector.source.MigrationSourceConnectorRegistry;
import com.etp.migration.connector.source.SourceColumn;
import com.etp.migration.connector.source.SourceInspection;
import com.etp.migration.entity.ArtifactFileFormat;
import com.etp.migration.entity.ArtifactType;
import com.etp.migration.entity.MigrationArtifact;
import com.etp.migration.entity.MigrationBatch;
import com.etp.migration.entity.MigrationBatchStatus;
import com.etp.migration.entity.MigrationRun;
import com.etp.migration.entity.MigrationRunStatus;
import com.etp.migration.entity.MigrationSource;
import com.etp.migration.entity.StorageProvider;
import com.etp.migration.migration.MigrationSourceNotFoundException;
import com.etp.migration.repository.MigrationArtifactRepository;
import com.etp.migration.repository.MigrationBatchRepository;
import com.etp.migration.repository.MigrationRunRepository;
import com.etp.migration.repository.MigrationSourceRepository;
import com.etp.migration.run.InvalidStateTransitionException;
import com.etp.migration.run.MigrationExecutionProgress;
import com.etp.migration.run.MigrationRunNotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.OptionalLong;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SourceInspectionService {

	private static final Logger log = LoggerFactory.getLogger(SourceInspectionService.class);
	static final String STATUS_SOURCE_INSPECTED = "SOURCE_INSPECTED";
	private static final String CHECKSUM_ALGORITHM = "SHA-256";

	private final TransactionTemplate transactionTemplate;
	private final MigrationRunRepository migrationRunRepository;
	private final MigrationSourceRepository migrationSourceRepository;
	private final MigrationBatchRepository migrationBatchRepository;
	private final MigrationArtifactRepository migrationArtifactRepository;
	private final MigrationSourceConnectorRegistry connectorRegistry;
	private final BatchPlanner batchPlanner;
	private final MigrationMappingRuleRepository mappingRuleRepository;
	private final EntityRelationshipPlanSource entityPlanSource;
	private final EntityFailureRecorder entityFailureRecorder;
	private final PreparationFailureRecorder preparationFailureRecorder;
	private final MigrationExecutionProgress progress;

	@PersistenceContext
	private EntityManager entityManager;

	public SourceInspectionService(
			PlatformTransactionManager transactionManager,
			MigrationRunRepository migrationRunRepository,
			MigrationSourceRepository migrationSourceRepository,
			MigrationBatchRepository migrationBatchRepository,
			MigrationArtifactRepository migrationArtifactRepository,
			MigrationSourceConnectorRegistry connectorRegistry,
			BatchPlanner batchPlanner,
			MigrationMappingRuleRepository mappingRuleRepository,
			EntityRelationshipPlanSource entityPlanSource,
			EntityFailureRecorder entityFailureRecorder,
			PreparationFailureRecorder preparationFailureRecorder,
			MigrationExecutionProgress progress) {
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.migrationRunRepository = migrationRunRepository;
		this.migrationSourceRepository = migrationSourceRepository;
		this.migrationBatchRepository = migrationBatchRepository;
		this.migrationArtifactRepository = migrationArtifactRepository;
		this.connectorRegistry = connectorRegistry;
		this.batchPlanner = batchPlanner;
		this.mappingRuleRepository = mappingRuleRepository;
		this.entityPlanSource = entityPlanSource;
		this.entityFailureRecorder = entityFailureRecorder;
		this.preparationFailureRecorder = preparationFailureRecorder;
		this.progress = progress;
	}

	public PrepareMigrationRunResponse prepare(UUID runId) {
		try {
			return inspectAndPlan(runId);
		} catch (RuntimeException ex) {
			preparationFailureRecorder.record(runId, ex);
			throw ex;
		}
	}

	private PrepareMigrationRunResponse inspectAndPlan(UUID runId) {
		InspectionWork work = transactionTemplate.execute(status -> beginPreparation(runId));
		if (work.alreadyPrepared()) {
			return work.existing();
		}
		if (work.entityMappingConfiguration() != null) {
			EntityPlan[] plan = new EntityPlan[1];
			PipelineTiming.open(() -> plan[0] = scanEntitySource(runId, work));
			return transactionTemplate.execute(status -> persistEntityPreparation(runId, work, plan[0]));
		}
		progress.mark(runId);
		long started = System.nanoTime();
		log.info("Source inspection started runId={}", runId);
		SourceInspection inspection = work.connector().inspect(work.source(), work.run());
		progress.mark(runId);
		log.info("Source inspection completed runId={} recordCount={} durationMs={}",
				runId, inspection.recordCount(), elapsedMillis(started));
		return transactionTemplate.execute(status -> persistPreparation(runId, work.sourceId(), inspection, work.batchSize()));
	}

	private InspectionWork beginPreparation(UUID runId) {
		MigrationRun run = migrationRunRepository.findById(runId)
				.orElseThrow(() -> new MigrationRunNotFoundException(runId));
		if (isPrepared(run)) {
			return InspectionWork.prepared(toResponse(run));
		}
		requirePreparable(run);
		MigrationSource source = resolveSource(run);
		int batchSize = run.getMigrationDefinition().getBatchSize() == null
				? 10_000
				: run.getMigrationDefinition().getBatchSize();
		source.getConfiguration();
		Map<String, Object> mappingConfiguration = mappingConfiguration(run);
		if (ExecutionMode.entityRelationship(mappingConfiguration)) {
			return InspectionWork.entity(run, source, batchSize, mappingConfiguration);
		}
		MigrationSourceConnector connector = connectorRegistry.require(source);
		return InspectionWork.pending(run, source, connector, batchSize);
	}

	private EntityPlan scanEntitySource(UUID runId, InspectionWork work) {
		List<MigrationMappingRule> rules = transactionTemplate.execute(status -> loadRules(runId));
		var model = EntityRelationshipConfiguration.parse(work.entityMappingConfiguration());
		long countStarted = System.nanoTime();
		log.info("Source count started runId={}", runId);
		OptionalLong sourceRows = entityPlanSource.physicalRowCount(
				work.source().getConfiguration(),
				model,
				rules,
				(stage, bytesRead, indexedRows) -> {
					progress.mark(runId);
					if (stage == SourceByteProgress.Stage.TRANSFER) {
						log.info("Source transfer progress runId={} bytesRead={}", runId, bytesRead);
					} else {
						log.info("Source index progress runId={} bytesRead={} rowsIndexed={}",
								runId, bytesRead, indexedRows);
					}
				});
		progress.mark(runId);
		log.info("Source count completed runId={} sourceRowCount={} durationMs={}",
				runId, sourceRows.isPresent() ? sourceRows.getAsLong() : null, elapsedMillis(countStarted));
		rejectDeclaredRowCount(work.source().getConfiguration(), sourceRows);
		long planningStarted = System.nanoTime();
		log.info("Entity planning started runId={}", runId);
		List<EntityItemFailure> structural = new ArrayList<>();
		List<PlannedEntityBatch> batches = new ArrayList<>();
		long[] counts = new long[2];
		entityPlanSource.consume(
				work.source().getConfiguration(),
				model,
				rules,
				work.batchSize(),
				plan -> {
					progress.mark(runId);
					batches.add(new PlannedEntityBatch(
							plan.batchNumber(),
							plan.workItems().size(),
							EntityBatchLocator.write(plan),
							sourceRecords(plan)));
					counts[0]++;
					counts[1] += plan.workItems().size();
				},
				structural::add);
		progress.mark(runId);
		log.info("Entity planning completed runId={} batches={} workItems={} durationMs={}",
				runId, counts[0], counts[1], elapsedMillis(planningStarted));
		return new EntityPlan(sourceRows, structural, batches, counts[0], counts[1]);
	}

	private List<MigrationMappingRule> loadRules(UUID runId) {
		MigrationRun run = migrationRunRepository.findById(runId)
				.orElseThrow(() -> new MigrationRunNotFoundException(runId));
		var mapping = run.getMigrationDefinition().getMapping();
		List<MigrationMappingRule> rules = mappingRuleRepository
				.findByMapping_IdAndEnabledTrueOrderBySequenceNoAsc(mapping.getId());
		rules.forEach(rule -> {
			rule.getEntityName();
			rule.getConfiguration();
		});
		return rules;
	}

	private PrepareMigrationRunResponse persistEntityPreparation(UUID runId, InspectionWork work, EntityPlan plan) {
		long started = System.nanoTime();
		MigrationRun run = migrationRunRepository.findByIdForUpdate(runId)
				.orElseThrow(() -> new MigrationRunNotFoundException(runId));
		if (isPrepared(run)) {
			return toResponse(run);
		}
		requirePreparable(run);
		Instant now = Instant.now();
		List<MigrationBatch> batches = new ArrayList<>();
		Map<Integer, Set<Long>> sourceRecordsByBatch = new LinkedHashMap<>();
		for (PlannedEntityBatch planned : plan.batches()) {
			MigrationBatch batch = new MigrationBatch();
			batch.setMigrationRun(run);
			batch.setBatchNumber(planned.batchNumber());
			batch.setStatus(MigrationBatchStatus.PENDING);
			batch.setRecordCount(planned.recordCount());
			batch.setSourceLocator(planned.sourceLocator());
			batch.setCreatedAt(now);
			batch.setUpdatedAt(now);
			migrationBatchRepository.save(batch);
			entityManager.flush();
			entityManager.detach(batch);
			batches.add(batch);
			sourceRecordsByBatch.put(planned.batchNumber(), planned.sourceRecords());
		}
		if (!plan.structural().isEmpty()) {
			entityFailureRecorder.save(runId, null, plan.structural());
			RelationshipFailurePlacement placement = placeRelationshipFailures(
					sourceRecordsByBatch,
					plan.structural().stream().map(EntityItemFailure::sourceRecordNumber).toList());
			for (MigrationBatch batch : batches) {
				int failures = placement.countsByBatchNumber().getOrDefault(batch.getBatchNumber(), 0);
				if (failures <= 0) {
					continue;
				}
				batch.setSourceLocator(EntityBatchLocator.withRelationshipFailures(batch.getSourceLocator(), failures));
				batch.setUpdatedAt(now);
				migrationBatchRepository.save(batch);
			}
			if (placement.unmatched() > 0) {
				long rejected = run.getRejectedRecords() == null ? 0L : run.getRejectedRecords();
				run.setRejectedRecords(rejected + placement.unmatched());
			}
		}
		run.setSourceSnapshot(entitySnapshot(work.sourceId(), plan.workItems(), plan.sourceRows()));
		run.setTotalRecords(plan.workItems());
		run.setTotalBatches(Math.toIntExact(plan.batchCount()));
		run.setUpdatedAt(now);
		progress.mark(runId);
		log.info("Preparation persisted runId={} batches={} workItems={} durationMs={}",
				runId, plan.batchCount(), plan.workItems(), elapsedMillis(started));
		return toResponse(run);
	}

	private static long elapsedMillis(long startedNanos) {
		return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
	}

	private record PlannedEntityBatch(
			int batchNumber,
			int recordCount,
			Map<String, Object> sourceLocator,
			Set<Long> sourceRecords
	) {
	}

	private record EntityPlan(
			OptionalLong sourceRows,
			List<EntityItemFailure> structural,
			List<PlannedEntityBatch> batches,
			long batchCount,
			long workItems
	) {
	}

	/**
	 * {@code recordCount} and {@code workItemCount} are logical execution work items.
	 * {@code sourceRowCount} is the physical CSV data-row count when a single positioned file supplied it.
	 * Reconciliation reads {@code recordCount}, so that field stays the work-item workload.
	 */
	static Map<String, Object> entitySnapshot(UUID sourceId, long workItemCount, OptionalLong sourceRows) {
		Map<String, Object> source = new LinkedHashMap<>();
		source.put("sourceId", sourceId);
		source.put("sourceType", "FILE");
		source.put("format", "CSV");
		source.put("recordCount", workItemCount);
		source.put("workItemCount", workItemCount);
		if (sourceRows != null && sourceRows.isPresent()) {
			source.put("sourceRowCount", sourceRows.getAsLong());
		}
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("statusMessage", STATUS_SOURCE_INSPECTED);
		snapshot.put("source", source);
		snapshot.put("executionMode", "ENTITY_RELATIONSHIP");
		return snapshot;
	}

	/**
	 * A {@code recordCount} on the source configuration is a declared physical row count.
	 * It is compared with the file. It does not replace the work-item workload.
	 */
	static void rejectDeclaredRowCount(Map<String, Object> configuration, OptionalLong sourceRows) {
		if (configuration == null || !configuration.containsKey("recordCount") || configuration.get("recordCount") == null) {
			return;
		}
		Object value = configuration.get("recordCount");
		if (!(value instanceof Number number) || number.doubleValue() != number.longValue()) {
			throw new InvalidSourceConfigurationException("Source recordCount must be a whole number of data rows");
		}
		if (sourceRows != null && sourceRows.isPresent() && number.longValue() != sourceRows.getAsLong()) {
			throw new InvalidSourceConfigurationException(
					"Source recordCount " + number.longValue()
							+ " does not match the " + sourceRows.getAsLong() + " data rows in the source file");
		}
	}

	private static Map<String, Object> mappingConfiguration(MigrationRun run) {
		if (run.getMigrationDefinition() == null || run.getMigrationDefinition().getMapping() == null) {
			return Map.of();
		}
		Map<String, Object> configuration = run.getMigrationDefinition().getMapping().getConfiguration();
		return configuration == null ? Map.of() : new LinkedHashMap<>(configuration);
	}

	private PrepareMigrationRunResponse persistPreparation(
			UUID runId,
			UUID sourceId,
			SourceInspection inspection,
			int batchSize) {
		MigrationRun run = migrationRunRepository.findByIdForUpdate(runId)
				.orElseThrow(() -> new MigrationRunNotFoundException(runId));
		if (isPrepared(run)) {
			return toResponse(run);
		}
		requirePreparable(run);
		Instant now = Instant.now();
		String objectKey = stringValue(inspection.metadata(), "objectKey");
		run.setSourceSnapshot(toSourceSnapshot(sourceId, inspection));
		run.setTotalRecords(inspection.recordCount());
		List<MigrationBatch> batches = new ArrayList<>();
		if (inspection.recordCount() > 0) {
			List<BatchPlanner.PlannedBatch> planned = batchPlanner.plan(
					run, new BatchPlanner.BatchPlanRequest(inspection.recordCount(), batchSize));
			for (BatchPlanner.PlannedBatch item : planned) {
				MigrationBatch batch = new MigrationBatch();
				batch.setMigrationRun(run);
				batch.setBatchNumber(item.batchNumber());
				batch.setStatus(MigrationBatchStatus.PENDING);
				batch.setRecordCount(item.recordCount());
				batch.setSourceLocator(csvRowRangeLocator(objectKey, item));
				batch.setSourceChecksum(inspection.checksum());
				batch.setCreatedAt(now);
				batch.setUpdatedAt(now);
				batches.add(batch);
			}
			migrationBatchRepository.saveAll(batches);
		}
		run.setTotalBatches(batches.size());
		run.setUpdatedAt(now);
		registerArtifact(run, inspection, objectKey);
		return toResponse(run);
	}

	private void registerArtifact(MigrationRun run, SourceInspection inspection, String objectKey) {
		if (migrationArtifactRepository.existsByMigrationRun_IdAndArtifactType(run.getId(), ArtifactType.RAW_SOURCE)) {
			return;
		}
		MigrationArtifact artifact = new MigrationArtifact();
		artifact.setMigrationRun(run);
		artifact.setArtifactType(ArtifactType.RAW_SOURCE);
		artifact.setStorageProvider(StorageProvider.valueOf(stringValue(inspection.metadata(), "provider")));
		artifact.setObjectKey(objectKey);
		artifact.setFileFormat(ArtifactFileFormat.valueOf(inspection.sourceFormat()));
		artifact.setFileSizeBytes(inspection.sizeBytes());
		artifact.setRecordCount(inspection.recordCount());
		artifact.setChecksumAlgorithm(CHECKSUM_ALGORITHM);
		artifact.setChecksum(inspection.checksum());
		migrationArtifactRepository.save(artifact);
	}

	private MigrationSource resolveSource(MigrationRun run) {
		UUID definitionId = run.getMigrationDefinition().getId();
		UUID requestedSourceId = extractSourceId(run.getSourceSnapshot());
		if (requestedSourceId == null) {
			requestedSourceId = extractSourceId(run.getConfigurationSnapshot());
		}
		if (requestedSourceId != null) {
			final UUID sourceId = requestedSourceId;
			return migrationSourceRepository
					.findByIdAndMigrationDefinition_Id(sourceId, definitionId)
					.filter(MigrationSource::isEnabled)
					.orElseThrow(() -> new MigrationSourceNotFoundException(sourceId));
		}
		List<MigrationSource> sources = migrationSourceRepository
				.findByMigrationDefinition_IdAndEnabledTrueOrderByCreatedAtAsc(definitionId);
		if (sources.isEmpty()) {
			throw new MigrationSourceNotFoundException("No enabled migration source is configured");
		}
		if (sources.size() > 1) {
			throw new InvalidSourceConfigurationException(
					"Migration definition has multiple enabled sources; specify sourceId");
		}
		return sources.get(0);
	}

	private static UUID extractSourceId(Map<String, Object> snapshot) {
		if (snapshot == null || snapshot.isEmpty()) {
			return null;
		}
		Object direct = snapshot.get("sourceId");
		if (direct == null && snapshot.get("source") instanceof Map<?, ?> nested) {
			direct = nested.get("sourceId");
		}
		if (direct == null) {
			return null;
		}
		try {
			return UUID.fromString(direct.toString());
		} catch (RuntimeException ex) {
			throw new InvalidSourceConfigurationException("sourceId is not a valid UUID");
		}
	}

	static RelationshipFailurePlacement placeRelationshipFailures(
			Map<Integer, Set<Long>> sourceRecordsByBatch,
			List<Long> failureSourceRecords) {
		Map<Long, Integer> batchByRecord = new HashMap<>();
		if (sourceRecordsByBatch != null) {
			for (Map.Entry<Integer, Set<Long>> entry : sourceRecordsByBatch.entrySet()) {
				if (entry.getValue() == null) {
					continue;
				}
				for (Long sourceRecord : entry.getValue()) {
					batchByRecord.putIfAbsent(sourceRecord, entry.getKey());
				}
			}
		}
		Map<Integer, Integer> counts = new LinkedHashMap<>();
		int unmatched = 0;
		if (failureSourceRecords != null) {
			for (Long sourceRecord : failureSourceRecords) {
				Integer batchNumber = batchByRecord.get(sourceRecord);
				if (batchNumber == null) {
					unmatched++;
				} else {
					counts.merge(batchNumber, 1, Integer::sum);
				}
			}
		}
		return new RelationshipFailurePlacement(Map.copyOf(counts), unmatched);
	}

	private static Set<Long> sourceRecords(ExecutionBatchPlan plan) {
		Set<Long> records = new LinkedHashSet<>();
		if (plan == null || plan.workItems() == null) {
			return records;
		}
		for (ExecutionWorkItem item : plan.workItems()) {
			if (item != null) {
				records.add(item.sourceRecordNumber());
			}
		}
		return records;
	}

	record RelationshipFailurePlacement(Map<Integer, Integer> countsByBatchNumber, int unmatched) {
	}

	private static void requirePreparable(MigrationRun run) {
		if (run.getStatus() != MigrationRunStatus.QUEUED && run.getStatus() != MigrationRunStatus.RUNNING) {
			throw new InvalidStateTransitionException(
					"Cannot prepare migration run in status " + run.getStatus());
		}
	}

	private static boolean isPrepared(MigrationRun run) {
		Object statusMessage = run.getSourceSnapshot() == null
				? null
				: run.getSourceSnapshot().get("statusMessage");
		return STATUS_SOURCE_INSPECTED.equals(statusMessage);
	}

	private static Map<String, Object> toSourceSnapshot(UUID sourceId, SourceInspection inspection) {
		Map<String, Object> checksum = new LinkedHashMap<>();
		checksum.put("algorithm", CHECKSUM_ALGORITHM);
		checksum.put("value", inspection.checksum());

		List<Map<String, Object>> columns = new ArrayList<>();
		for (SourceColumn column : inspection.columns()) {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("name", column.name());
			item.put("inferredType", column.inferredType());
			item.put("ordinalPosition", column.ordinalPosition());
			item.put("nullable", column.nullable());
			columns.add(item);
		}

		Map<String, Object> source = new LinkedHashMap<>();
		source.put("sourceId", sourceId);
		source.put("sourceType", "FILE");
		source.put("provider", inspection.metadata().get("provider"));
		source.put("objectKey", inspection.metadata().get("objectKey"));
		source.put("format", inspection.sourceFormat());
		source.put("sizeBytes", inspection.sizeBytes());
		source.put("checksum", checksum);
		source.put("recordCount", inspection.recordCount());
		source.put("columns", columns);

		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("statusMessage", STATUS_SOURCE_INSPECTED);
		snapshot.put("source", source);
		return snapshot;
	}

	private Map<String, Object> csvRowRangeLocator(String objectKey, BatchPlanner.PlannedBatch item) {
		Map<String, Object> locator = new LinkedHashMap<>();
		locator.put("type", "CSV_ROW_RANGE");
		locator.put("objectKey", objectKey);
		locator.put("startRecord", item.startOffset() + 1);
		locator.put("endRecord", item.endOffset() + 1);
		return locator;
	}

	private PrepareMigrationRunResponse toResponse(MigrationRun run) {
		Map<String, Object> source = nestedMap(run.getSourceSnapshot(), "source");
		String checksum = null;
		Long recordCount = run.getTotalRecords();
		if (source != null) {
			checksum = nestedString(nestedMap(source, "checksum"), "value");
			Object count = source.get("recordCount");
			if (count instanceof Number number) {
				recordCount = number.longValue();
			}
		}
		int batchSize = run.getMigrationDefinition().getBatchSize() == null
				? 10_000
				: run.getMigrationDefinition().getBatchSize();
		return new PrepareMigrationRunResponse(
				run.getId(),
				run.getStatus().name(),
				recordCount,
				batchSize,
				run.getTotalBatches(),
				checksum,
				STATUS_SOURCE_INSPECTED);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> nestedMap(Map<String, Object> parent, String key) {
		if (parent == null) {
			return null;
		}
		Object value = parent.get(key);
		if (value instanceof Map<?, ?> map) {
			return (Map<String, Object>) map;
		}
		return null;
	}

	private static String nestedString(Map<String, Object> parent, String key) {
		if (parent == null || parent.get(key) == null) {
			return null;
		}
		return parent.get(key).toString();
	}

	private static String stringValue(Map<String, Object> metadata, String key) {
		if (metadata == null || metadata.get(key) == null) {
			throw new InvalidSourceConfigurationException("Source inspection metadata is incomplete");
		}
		return metadata.get(key).toString();
	}

	private record InspectionWork(
			boolean alreadyPrepared,
			PrepareMigrationRunResponse existing,
			MigrationRun run,
			MigrationSource source,
			MigrationSourceConnector connector,
			int batchSize,
			Map<String, Object> entityMappingConfiguration
	) {
		static InspectionWork prepared(PrepareMigrationRunResponse existing) {
			return new InspectionWork(true, existing, null, null, null, 0, null);
		}

		static InspectionWork pending(
				MigrationRun run,
				MigrationSource source,
				MigrationSourceConnector connector,
				int batchSize) {
			return new InspectionWork(false, null, run, source, connector, batchSize, null);
		}

		static InspectionWork entity(
				MigrationRun run,
				MigrationSource source,
				int batchSize,
				Map<String, Object> mappingConfiguration) {
			return new InspectionWork(false, null, run, source, null, batchSize, mappingConfiguration);
		}

		UUID sourceId() {
			return source == null ? null : source.getId();
		}
	}
}
