package com.etp.migration.execution;

import com.etp.migration.connector.target.MigrationTargetConnector;
import com.etp.migration.connector.target.MigrationTargetConnectorRegistry;
import com.etp.migration.identity.EntityIdentity;
import com.etp.migration.identity.TargetIdentityLookup;
import com.etp.migration.mapping.EntityRelationshipConfiguration;
import com.etp.migration.mapping.EntityRelationshipMapping;
import com.etp.migration.planning.ExecutionBatchPlan;
import com.etp.migration.planning.ExecutionWorkItem;
import com.etp.migration.processing.BatchProcessingException;
import com.etp.migration.processing.MigrationErrorCodes;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Processes one stored entity batch. Source payloads for that batch are released before the method returns.
 * Target identity allocation is not performed here. A configured {@link TargetIdentityLookup} may still
 * supply an id. A missing id is left null and the entity is still sent to the target connector.
 */
@Component
public class EntityRelationshipBatchProcessor {

	public static final int CHUNK_SIZE = 100;

	private final EntityCsvAccess csvAccess;
	private final EntityRelationshipBatchExecutor executor;
	private final MigrationTargetConnectorRegistry targetConnectorRegistry;
	private final ObjectProvider<TargetIdentityLookup> identityLookups;

	@Autowired
	public EntityRelationshipBatchProcessor(
			EntityCsvAccess csvAccess,
			EntityTargetComposer composer,
			MigrationTargetConnectorRegistry targetConnectorRegistry,
			ObjectProvider<TargetIdentityLookup> identityLookups) {
		this.csvAccess = csvAccess;
		this.executor = new EntityRelationshipBatchExecutor(composer);
		this.targetConnectorRegistry = targetConnectorRegistry;
		this.identityLookups = identityLookups;
	}

	public EntityBatchOutcome process(StoredEntityBatch batch) {
		EntityBatchOutcome[] outcome = new EntityBatchOutcome[1];
		com.etp.migration.processing.PipelineTiming.open(() -> outcome[0] = processMeasured(batch));
		return outcome[0];
	}

	private EntityBatchOutcome processMeasured(StoredEntityBatch batch) {
		EntityRelationshipMapping mapping = EntityRelationshipConfiguration.parse(batch.mappingConfiguration());
		ExecutionBatchPlan plan = EntityBatchLocator.read(batch.sourceLocator());
		if (plan.workItems().size() != batch.recordCount()) {
			throw new BatchProcessingException(
					MigrationErrorCodes.SOURCE_LOCATOR_INVALID,
					"Entity batch record count does not match the stored work items");
		}
		Set<EntityIdentity> wanted = new LinkedHashSet<>();
		for (ExecutionWorkItem item : plan.workItems()) {
			wanted.add(new EntityIdentity(item.entityName(), item.sourceIdentity()));
		}
		EntityCsvRecords records = csvAccess.open(batch.sourceConfiguration(), mapping, batch.rules());
		Map<EntityIdentity, com.etp.migration.extraction.EntityRecord> payloads = records.matching(wanted);
		try {
			MigrationTargetConnector target = targetConnectorRegistry.require(batch.targetType());
			TargetIdentityLookup lookup = identityLookups.getIfAvailable(this::unresolvedLookup);
			return executor.execute(
					mapping,
					batch.rules(),
					plan,
					payloads,
					lookup,
					CHUNK_SIZE,
					target,
					EntityRelationshipBatchExecutor.context(
							batch.runId(),
							batch.batchId(),
							batch.batchNumber(),
							batch.targetType(),
							batch.targetEntity(),
							batch.loadMode(),
							batch.targetConfiguration()));
		} finally {
			payloads.clear();
		}
	}

	private TargetIdentityLookup unresolvedLookup() {
		return (entityName, sourceIdentities) -> Map.of();
	}
}
