package com.etp.migration.execution;

import com.etp.migration.entity.MigrationMappingRule;
import com.etp.migration.entity.MigrationStage;
import com.etp.migration.extraction.EntityExtractionFailure;
import com.etp.migration.extraction.EntityRecord;
import com.etp.migration.mapping.EntityRelationshipMapping;
import com.etp.migration.planning.ExecutionBatchPlan;
import com.etp.migration.planning.ExecutionPlanner;
import com.etp.migration.processing.MigrationErrorCodes;
import com.etp.migration.relationship.EntityRecordSource;
import com.etp.migration.relationship.RelationshipResolution;
import com.etp.migration.relationship.RelationshipResolutionFailure;
import com.etp.migration.relationship.RelationshipResolver;
import com.etp.migration.scope.LogicalMigrationScope;
import com.etp.migration.scope.LogicalMigrationScopeBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Streams execution batches from CSV or from already extracted entity records.
 * One batch is handed to the consumer at a time. Source payloads are not retained on the plan.
 */
@Component
public class EntityRelationshipPlanSource {

	private final EntityCsvAccess csvAccess;
	private final RelationshipResolver relationshipResolver;
	private final LogicalMigrationScopeBuilder scopeBuilder;
	private final ExecutionPlanner executionPlanner;

	public EntityRelationshipPlanSource(
			EntityCsvAccess csvAccess,
			RelationshipResolver relationshipResolver,
			LogicalMigrationScopeBuilder scopeBuilder,
			ExecutionPlanner executionPlanner) {
		this.csvAccess = csvAccess;
		this.relationshipResolver = relationshipResolver;
		this.scopeBuilder = scopeBuilder;
		this.executionPlanner = executionPlanner;
	}

	public void consume(
			Map<String, Object> sourceConfiguration,
			EntityRelationshipMapping mapping,
			List<MigrationMappingRule> rules,
			int batchSize,
			Consumer<ExecutionBatchPlan> batches,
			Consumer<EntityItemFailure> failures) {
		EntityCsvRecords records = csvAccess.open(sourceConfiguration, mapping, rules);
		consume(mapping, records.byEntity(), records::all, batchSize, batches, failure -> {
			failures.accept(failure);
		}, records);
	}

	public void consume(
			EntityRelationshipMapping mapping,
			List<EntityRecord> records,
			int batchSize,
			Consumer<ExecutionBatchPlan> batches,
			Consumer<EntityItemFailure> failures) {
		EntityRecordSource source = entityName -> records.stream()
				.filter(record -> record != null && entityName.equals(record.entityName()));
		consume(mapping, source, records::stream, batchSize, batches, failures, null);
	}

	private void consume(
			EntityRelationshipMapping mapping,
			EntityRecordSource source,
			java.util.function.Supplier<Stream<EntityRecord>> entities,
			int batchSize,
			Consumer<ExecutionBatchPlan> batches,
			Consumer<EntityItemFailure> failures,
			EntityCsvRecords csvRecords) {
		RelationshipResolution resolution = com.etp.migration.processing.PipelineTiming.time(
				com.etp.migration.processing.PipelineTiming.Stage.RELATIONSHIP_RESOLUTION,
				() -> relationshipResolver.resolve(mapping, source));
		Stream<LogicalMigrationScope> scopes = com.etp.migration.processing.PipelineTiming.time(
				com.etp.migration.processing.PipelineTiming.Stage.SCOPE_BUILD,
				() -> scopeBuilder.build(mapping, entities.get(), resolution.relationships().stream()));
		if (csvRecords != null) {
			for (EntityExtractionFailure failure : csvRecords.extractionFailures()) {
				failures.accept(extraction(failure));
			}
		}
		for (RelationshipResolutionFailure failure : resolution.failures()) {
			failures.accept(relationship(failure));
		}
		try (Stream<ExecutionBatchPlan> plans = executionPlanner.plan(mapping, scopes, batchSize)) {
			com.etp.migration.processing.PipelineTiming.time(
					com.etp.migration.processing.PipelineTiming.Stage.PLANNING,
					() -> {
						plans.forEach(batches);
						return null;
					});
		}
	}

	private static EntityItemFailure extraction(EntityExtractionFailure failure) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("entityName", failure.entityName());
		details.put("sourceIdentity", "");
		details.put("sourceKey", failure.sourceKey());
		details.put("stage", MigrationStage.EXTRACT.name());
		return new EntityItemFailure(
				failure.sourceRecordNumber(),
				failure.entityName(),
				null,
				MigrationStage.EXTRACT,
				MigrationErrorCodes.ENTITY_EXTRACTION_FAILED,
				"ENTITY_EXTRACTION_FAILED",
				failure.sourceKey(),
				failure.message(),
				details,
				false);
	}

	private static EntityItemFailure relationship(RelationshipResolutionFailure failure) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("entityName", failure.childEntity());
		details.put("sourceIdentity", failure.childIdentity() == null ? "" : failure.childIdentity());
		details.put("parentEntity", failure.parentEntity() == null ? "" : failure.parentEntity());
		details.put("stage", MigrationStage.EXTRACT.name());
		String code = failure.kind() == RelationshipResolutionFailure.Kind.UNRESOLVED_PARENT
				? MigrationErrorCodes.RELATIONSHIP_UNRESOLVED
				: "MIGRATION_RELATIONSHIP_" + failure.kind().name();
		return new EntityItemFailure(
				failure.childSourceRecordNumber() == null ? 0L : failure.childSourceRecordNumber(),
				failure.childEntity(),
				failure.childIdentity(),
				MigrationStage.EXTRACT,
				code,
				failure.kind().name(),
				null,
				failure.message(),
				details,
				false);
	}
}
