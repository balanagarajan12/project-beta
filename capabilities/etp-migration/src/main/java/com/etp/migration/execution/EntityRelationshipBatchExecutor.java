package com.etp.migration.execution;

import com.etp.migration.batch.BatchCompletionCounters;
import com.etp.migration.connector.target.LoadableTargetRecord;
import com.etp.migration.connector.target.MigrationTargetConnector;
import com.etp.migration.connector.target.MigrationTargetContext;
import com.etp.migration.connector.target.TargetInfrastructureException;
import com.etp.migration.connector.target.TargetLoadModeNotSupportedException;
import com.etp.migration.connector.target.TargetLoadResult;
import com.etp.migration.connector.target.TargetRecordError;
import com.etp.migration.connector.target.UnsupportedTargetTypeException;
import com.etp.migration.entity.LoadMode;
import com.etp.migration.entity.MigrationMappingRule;
import com.etp.migration.entity.MigrationStage;
import com.etp.migration.extraction.EntityRecord;
import com.etp.migration.identity.EntityIdentity;
import com.etp.migration.identity.IdentityResolutionResult;
import com.etp.migration.identity.IdentityResolutionService;
import com.etp.migration.identity.ResolvedIdentity;
import com.etp.migration.identity.TargetIdentityLookup;
import com.etp.migration.identity.UnresolvedIdentity;
import com.etp.migration.mapping.EntityRelationshipMapping;
import com.etp.migration.planning.ExecutionBatchPlan;
import com.etp.migration.planning.ExecutionWorkItem;
import com.etp.migration.processing.BatchProcessingException;
import com.etp.migration.processing.MigrationErrorCodes;
import com.etp.migration.processing.ProcessingError;
import com.etp.migration.processing.TargetRecord;
import com.etp.migration.processing.TransformationException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Maps, validates, resolves identities, and loads one execution batch.
 * The batch plan is not modified. Payloads passed in are not retained after the call.
 */
public class EntityRelationshipBatchExecutor {

	private final EntityTargetComposer composer;
	private final IdentityResolutionService identityResolutionService;

	public EntityRelationshipBatchExecutor(EntityTargetComposer composer) {
		this(composer, new IdentityResolutionService());
	}

	EntityRelationshipBatchExecutor(EntityTargetComposer composer, IdentityResolutionService identityResolutionService) {
		this.composer = composer;
		this.identityResolutionService = identityResolutionService;
	}

	public EntityBatchOutcome execute(
			EntityRelationshipMapping mapping,
			List<MigrationMappingRule> rules,
			ExecutionBatchPlan plan,
			Map<EntityIdentity, EntityRecord> payloads,
			TargetIdentityLookup lookup,
			int chunkSize,
			MigrationTargetConnector target,
			MigrationTargetContext context) {
		if (chunkSize < 1) {
			throw new IllegalArgumentException("chunkSize must be greater than 0");
		}
		IdentityResolutionResult identities = identityResolutionService.resolve(mapping, plan, lookup, chunkSize);
		Map<EntityIdentity, ResolvedIdentity> resolved = new LinkedHashMap<>();
		Map<EntityIdentity, UnresolvedIdentity> unresolved = new LinkedHashMap<>();
		for (ResolvedIdentity identity : identities.resolved()) {
			resolved.put(identity.source(), identity);
		}
		for (UnresolvedIdentity identity : identities.unresolved()) {
			unresolved.put(identity.source(), identity);
		}
		List<EntityItemFailure> failures = new ArrayList<>();
		List<LoadableTargetRecord> loadable = new ArrayList<>();
		List<ExecutionWorkItem> submitted = new ArrayList<>();
		int processed = 0;
		int validationFailures = 0;
		int rejected = 0;
		int blocked = 0;
		int duplicates = 0;
		java.util.Set<String> loadedKeys = new java.util.LinkedHashSet<>();
		for (ExecutionWorkItem item : plan.workItems()) {
			processed++;
			EntityIdentity identity = new EntityIdentity(item.entityName(), item.sourceIdentity());
			EntityRecord record = payloads.get(identity);
			if (record == null) {
				rejected++;
				failures.add(failure(
						item,
						MigrationStage.EXTRACT,
						MigrationErrorCodes.ENTITY_EXTRACTION_FAILED,
						"ENTITY_PAYLOAD_NOT_FOUND",
						null,
						"Source payload for entity '" + item.entityName() + "' source '" + item.sourceIdentity()
								+ "' was not found.",
						false));
				continue;
			}
			TargetRecord attributes;
			try {
				attributes = composer.map(record, rules);
			} catch (RuntimeException ex) {
				rejected++;
				TransformationException transformation = composer.asTransformation(ex);
				String code = transformation == null
						? MigrationErrorCodes.TRANSFORMATION_ERROR
						: transformation.errorCode();
				String message = ex.getMessage() == null ? "Transformation failed" : ex.getMessage();
				failures.add(failure(
						item,
						MigrationStage.TRANSFORM,
						code,
						"TRANSFORMATION_FAILED",
						transformation == null ? null : transformation.fieldName(),
						message,
						false));
				continue;
			}
			List<ProcessingError> validation = composer.validate(attributes, rules, item.entityName());
			if (!validation.isEmpty()) {
				validationFailures++;
				for (ProcessingError error : validation) {
					failures.add(new EntityItemFailure(
							item.sourceRecordNumber(),
							item.entityName(),
							item.sourceIdentity(),
							error.stage(),
							error.errorCode(),
							error.errorType(),
							error.fieldName(),
							error.message(),
							details(item, error.details()),
							error.retryable()));
				}
				continue;
			}
			UnresolvedIdentity missing = unresolved.get(identity);
			ResolvedIdentity found = resolved.get(identity);
			Map<String, Object> payload = found == null
					? composer.payload(
							mapping,
							item.entityName(),
							item.sourceIdentity(),
							null,
							record,
							attributes,
							missing == null ? List.of() : missing.parents())
					: composer.payload(mapping, found, record, attributes);
			String idempotencyKey = com.etp.migration.processing.EntityIdempotencyKey.of(
					context.migrationRunId(),
					item.entityName(),
					item.sourceIdentity());
			if (!loadedKeys.add(idempotencyKey)) {
				duplicates++;
				continue;
			}
			loadable.add(new LoadableTargetRecord(
					item.sourceRecordNumber(),
					new TargetRecord(payload),
					idempotencyKey));
			submitted.add(item);
		}
		int success = 0;
		int loadFailures = 0;
		if (!loadable.isEmpty()) {
			long started = com.etp.migration.processing.PipelineTiming.start();
			try {
				TargetLoadResult loaded = load(target, context, loadable);
				success = loaded.successCount();
				loadFailures = loaded.failedCount();
				failures.addAll(targetFailures(submitted, loaded.errors()));
			} finally {
				com.etp.migration.processing.PipelineTiming.stop(
						com.etp.migration.processing.PipelineTiming.Stage.TARGET_LOAD,
						started);
			}
		}
		return new EntityBatchOutcome(
				new BatchCompletionCounters(processed, success, duplicates, validationFailures, 0, blocked + loadFailures, rejected),
				failures);
	}

	private static TargetLoadResult load(
			MigrationTargetConnector target,
			MigrationTargetContext context,
			List<LoadableTargetRecord> loadable) {
		try {
			LoadMode loadMode = context.loadMode() == null ? LoadMode.SINGLE : context.loadMode();
			if (!target.supportsLoadMode(loadMode)) {
				throw new TargetLoadModeNotSupportedException(context.targetType(), loadMode);
			}
			return target.load(context, loadable);
		} catch (TargetLoadModeNotSupportedException ex) {
			throw new BatchProcessingException(MigrationErrorCodes.TARGET_LOAD_MODE_NOT_SUPPORTED, ex.getMessage(), ex);
		} catch (UnsupportedTargetTypeException ex) {
			throw new BatchProcessingException(MigrationErrorCodes.TARGET_TYPE_UNSUPPORTED, ex.getMessage(), ex);
		} catch (TargetInfrastructureException ex) {
			throw new BatchProcessingException(ex.errorCode(), "Target load failed", ex);
		}
	}

	private static List<EntityItemFailure> targetFailures(
			List<ExecutionWorkItem> submitted,
			List<TargetRecordError> errors) {
		List<EntityItemFailure> failures = new ArrayList<>();
		if (errors == null) {
			return failures;
		}
		for (TargetRecordError error : errors) {
			ExecutionWorkItem item = submitted.stream()
					.filter(candidate -> candidate.sourceRecordNumber() == error.sourceRecordNumber())
					.findFirst()
					.orElse(null);
			String entityName = item == null ? "" : item.entityName();
			String sourceIdentity = item == null ? "" : item.sourceIdentity();
			long recordNumber = item == null ? error.sourceRecordNumber() : item.sourceRecordNumber();
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("entityName", entityName);
			details.put("sourceIdentity", sourceIdentity);
			details.put("stage", MigrationStage.LOAD.name());
			if (error.httpStatus() != null) {
				details.put("httpStatus", error.httpStatus());
			}
			failures.add(new EntityItemFailure(
					recordNumber,
					entityName,
					sourceIdentity,
					MigrationStage.LOAD,
					error.errorCode(),
					error.errorType(),
					null,
					error.message(),
					details,
					error.retryable()));
		}
		return failures;
	}

	private static EntityItemFailure failure(
			ExecutionWorkItem item,
			MigrationStage stage,
			String code,
			String type,
			String fieldName,
			String message,
			boolean retryable) {
		return new EntityItemFailure(
				item.sourceRecordNumber(),
				item.entityName(),
				item.sourceIdentity(),
				stage,
				code,
				type,
				fieldName,
				message,
				details(item, Map.of()),
				retryable);
	}

	private static Map<String, Object> details(ExecutionWorkItem item, Map<String, Object> extra) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("entityName", item.entityName());
		details.put("sourceIdentity", item.sourceIdentity() == null ? "" : item.sourceIdentity());
		details.put("rootEntity", item.rootEntity());
		details.put("rootIdentity", item.rootIdentity());
		if (extra != null) {
			details.putAll(extra);
		}
		return details;
	}

	static MigrationTargetContext context(
			UUID runId,
			long batchId,
			int batchNumber,
			String targetType,
			String targetEntity,
			LoadMode loadMode,
			Map<String, Object> configuration) {
		return new MigrationTargetContext(
				runId,
				batchId,
				batchNumber,
				targetType,
				targetEntity,
				loadMode,
				configuration == null ? Map.of() : configuration);
	}
}
