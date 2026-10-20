package com.etp.migration.processing;

import com.etp.migration.batch.BatchPlanner;
import com.etp.migration.connector.source.InvalidSourceConfigurationException;
import com.etp.migration.entity.MigrationMappingRule;
import com.etp.migration.execution.EntityBatchLocator;
import com.etp.migration.execution.EntityFailureRecorder;
import com.etp.migration.execution.EntityItemFailure;
import com.etp.migration.execution.EntityRelationshipPlanSource;
import com.etp.migration.execution.ExecutionMode;
import com.etp.migration.mapping.EntityRelationshipConfiguration;
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
import com.etp.migration.run.MigrationRunNotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SourceInspectionService {

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
			PreparationFailureRecorder preparationFailureRecorder) {
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
			PrepareMigrationRunResponse[] response = new PrepareMigrationRunResponse[1];
			PipelineTiming.open(() -> response[0] = transactionTemplate.execute(status -> persistEntityPreparation(runId, work)));
			return response[0];
		}
		SourceInspection inspection = work.connector().inspect(work.source(), work.run());
		return transactionTemplate.execute(status -> persistPreparation(runId, work.sourceId(), inspection, work.batchSize()));
	}

	private InspectionWork beginPreparation(UUID runId) {
		MigrationRun run = migrationRunRepository.findById(runId)
				.orElseThrow(() -> new MigrationRunNotFoundException(runId));
		if (isPrepared(run)) {
			return InspectionWork.prepared(toResponse(run));
		}
		if (run.getStatus() != MigrationRunStatus.QUEUED) {
			throw new InvalidStateTransitionException(
					"Cannot prepare migration run in status " + run.getStatus());
		}
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

	private PrepareMigrationRunResponse persistEntityPreparation(UUID runId, InspectionWork work) {
		MigrationRun run = migrationRunRepository.findById(runId)
				.orElseThrow(() -> new MigrationRunNotFoundException(runId));
		if (isPrepared(run)) {
			return toResponse(run);
		}
		if (run.getStatus() != MigrationRunStatus.QUEUED) {
			throw new InvalidStateTransitionException(
					"Cannot prepare migration run in status " + run.getStatus());
		}
		var mapping = run.getMigrationDefinition().getMapping();
		List<MigrationMappingRule> rules = mappingRuleRepository
				.findByMapping_IdAndEnabledTrueOrderBySequenceNoAsc(mapping.getId());
		rules.forEach(rule -> {
			rule.getEntityName();
			rule.getConfiguration();
		});
		var model = EntityRelationshipConfiguration.parse(work.entityMappingConfiguration());
		List<EntityItemFailure> structural = new ArrayList<>();
		int[] counts = new int[2];
		Instant now = Instant.now();
		entityPlanSource.consume(
				work.source().getConfiguration(),
				model,
				rules,
				work.batchSize(),
				plan -> {
					MigrationBatch batch = new MigrationBatch();
					batch.setMigrationRun(run);
					batch.setBatchNumber(plan.batchNumber());
					batch.setStatus(MigrationBatchStatus.PENDING);
					batch.setRecordCount(plan.workItems().size());
					batch.setSourceLocator(EntityBatchLocator.write(plan));
					batch.setCreatedAt(now);
					batch.setUpdatedAt(now);
					migrationBatchRepository.save(batch);
					entityManager.flush();
					entityManager.detach(batch);
					counts[0]++;
					counts[1] += plan.workItems().size();
				},
				structural::add);
		run.setSourceSnapshot(entitySnapshot(work.sourceId(), counts[1]));
		run.setTotalRecords((long) counts[1]);
		run.setTotalBatches(counts[0]);
		run.setUpdatedAt(now);
		if (!structural.isEmpty()) {
			entityFailureRecorder.save(runId, null, structural);
		}
		return toResponse(run);
	}

	private static Map<String, Object> entitySnapshot(UUID sourceId, int workItemCount) {
		Map<String, Object> source = new LinkedHashMap<>();
		source.put("sourceId", sourceId);
		source.put("sourceType", "FILE");
		source.put("format", "CSV");
		source.put("recordCount", workItemCount);
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("statusMessage", STATUS_SOURCE_INSPECTED);
		snapshot.put("source", source);
		snapshot.put("executionMode", "ENTITY_RELATIONSHIP");
		return snapshot;
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
		MigrationRun run = migrationRunRepository.findById(runId)
				.orElseThrow(() -> new MigrationRunNotFoundException(runId));
		if (isPrepared(run)) {
			return toResponse(run);
		}
		if (run.getStatus() != MigrationRunStatus.QUEUED) {
			throw new InvalidStateTransitionException(
					"Cannot prepare migration run in status " + run.getStatus());
		}
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
