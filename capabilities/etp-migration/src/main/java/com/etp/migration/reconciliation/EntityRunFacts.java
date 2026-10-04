package com.etp.migration.reconciliation;

import com.etp.migration.connector.target.StagedEntityView;
import com.etp.migration.connector.target.StagedRelationshipView;
import com.etp.migration.execution.EntityBatchLocator;
import com.etp.migration.planning.ExecutionBatchPlan;
import com.etp.migration.planning.ExecutionWorkItem;
import com.etp.migration.planning.WorkItemParent;
import com.etp.migration.processing.BatchProcessingException;
import com.etp.migration.processing.MigrationErrorCodes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads execution facts that the pipeline already stored.
 * It does not open source files, resolve identities, or resolve relationships again.
 */
public final class EntityRunFacts {

	private EntityRunFacts() {
	}

	public static void foldLocator(EntityReconciliationAccumulator accumulator, Map<String, Object> locator) {
		if (!EntityBatchLocator.isEntityBatch(locator)) {
			return;
		}
		ExecutionBatchPlan plan;
		try {
			plan = EntityBatchLocator.read(locator);
		} catch (BatchProcessingException ex) {
			accumulator.noteRelationshipIssue("", "", "", MigrationErrorCodes.SOURCE_LOCATOR_INVALID);
			return;
		}
		for (ExecutionWorkItem item : plan.workItems()) {
			List<String> parents = new ArrayList<>();
			for (WorkItemParent parent : item.parents()) {
				parents.add(parent.entityName());
			}
			accumulator.notePlanned(item.entityName(), item.sourceIdentity(), parents);
		}
	}

	public static void foldError(
			EntityReconciliationAccumulator accumulator,
			String errorCode,
			String errorType,
			String sourceRecordKey,
			Map<String, Object> details) {
		Map<String, Object> values = details == null ? Map.of() : details;
		String entityName = text(values.get("entityName"));
		String sourceIdentity = text(values.get("sourceIdentity"));
		if (sourceIdentity == null) {
			sourceIdentity = text(sourceRecordKey);
		}
		String parentEntity = text(values.get("parentEntity"));
		if (MigrationErrorCodes.ENTITY_EXTRACTION_FAILED.equals(errorCode)) {
			if (accumulator.wasPlanned(entityName, sourceIdentity)) {
				accumulator.noteFailure(entityName, sourceIdentity, EntityOutcome.FAILURE, errorCode);
			}
			return;
		}
		if (MigrationErrorCodes.TARGET_IDENTITY_UNRESOLVED.equals(errorCode)
				|| MigrationErrorCodes.IDENTITY_LOOKUP_FAILED.equals(errorCode)) {
			accumulator.noteFailure(entityName, sourceIdentity, EntityOutcome.IDENTITY_UNRESOLVED, errorCode);
			return;
		}
		if (MigrationErrorCodes.PARENT_TARGET_UNRESOLVED.equals(errorCode)) {
			accumulator.noteFailure(entityName, sourceIdentity, EntityOutcome.LOAD_FAILURE, errorCode);
			accumulator.noteRelationshipIssue(parentEntity, entityName, sourceIdentity, RelationshipCondition.PARENT_TARGET_UNRESOLVED.name());
			return;
		}
		if (MigrationErrorCodes.RELATIONSHIP_UNRESOLVED.equals(errorCode)) {
			accumulator.noteRelationshipIssue(parentEntity, entityName, sourceIdentity, RelationshipCondition.REFERENCE_MISSING.name());
			return;
		}
		if (isCardinality(errorCode, errorType)) {
			accumulator.noteRelationshipIssue(
					parentEntity,
					entityName,
					sourceIdentity,
					RelationshipCondition.CARDINALITY_VIOLATION.name());
			return;
		}
		if (MigrationErrorCodes.VALIDATION_ERROR.equals(errorCode)
				|| MigrationErrorCodes.TRANSFORMATION_ERROR.equals(errorCode)) {
			accumulator.noteFailure(entityName, sourceIdentity, EntityOutcome.FAILURE, errorCode);
			return;
		}
		if (isLoad(errorCode)) {
			accumulator.noteFailure(entityName, sourceIdentity, EntityOutcome.LOAD_FAILURE, errorCode);
		}
	}

	public static void foldStaged(EntityReconciliationAccumulator accumulator, StagedEntityView view) {
		if (view == null || view.entityName() == null || view.entityName().isBlank()) {
			return;
		}
		List<RelationshipFact> links = new ArrayList<>();
		for (StagedRelationshipView relationship : view.relationships()) {
			if (relationship.parentEntity() == null || relationship.parentEntity().isBlank()) {
				continue;
			}
			links.add(RelationshipFact.observed(
					relationship.parentEntity(),
					relationship.parentSourceIdentity(),
					relationship.parentTargetIdentity(),
					view.targetIdentity()));
		}
		accumulator.noteExecutionSuccess(view.entityName(), view.sourceIdentity(), view.targetIdentity(), links);
	}

	private static boolean isCardinality(String errorCode, String errorType) {
		return "CARDINALITY_VIOLATION".equals(errorType)
				|| "DUPLICATE_PARENT_IDENTITY".equals(errorType)
				|| (errorCode != null && errorCode.contains("CARDINALITY"));
	}

	private static boolean isLoad(String errorCode) {
		return MigrationErrorCodes.TARGET_COPY_FAILED.equals(errorCode)
				|| MigrationErrorCodes.TARGET_CONNECTION_ERROR.equals(errorCode)
				|| MigrationErrorCodes.TARGET_TIMEOUT.equals(errorCode)
				|| MigrationErrorCodes.TARGET_4XX.equals(errorCode)
				|| MigrationErrorCodes.TARGET_5XX.equals(errorCode)
				|| MigrationErrorCodes.TARGET_INVALID_RESPONSE.equals(errorCode);
	}

	private static String text(Object value) {
		if (value == null) {
			return null;
		}
		String text = value.toString().trim();
		return text.isEmpty() ? null : text;
	}
}
