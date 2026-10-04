package com.etp.migration.reconciliation;

import com.etp.migration.entity.ReconciliationStatus;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Folds entity execution facts into aggregate counters and a bounded example list.
 * Successful identities, payloads, and relationships are not retained.
 * The fold does not modify execution, source data, or target data.
 */
public final class EntityReconciliationAccumulator {

	public static final int DEFAULT_EXAMPLE_LIMIT = 100;
	public static final String UNAVAILABLE = "unavailable";
	public static final String UNRESOLVED_TARGET = "unresolved";

	private final int exampleLimit;
	private final TreeMap<String, EntityCounter> entities = new TreeMap<>();
	private final TreeMap<EdgeKey, EdgeCounter> relationships = new TreeMap<>();
	private final List<Map<String, Object>> examples = new ArrayList<>();
	private final Set<String> plannedIdentities = new HashSet<>();
	private long examplesOmitted;
	private long batchesAggregated;
	private int retainedObservations;
	private TargetResultState targetResults = TargetResultState.UNKNOWN;
	private TargetCountState targetCountState = TargetCountState.UNSPECIFIED;
	private Long targetCount;

	public EntityReconciliationAccumulator() {
		this(DEFAULT_EXAMPLE_LIMIT);
	}

	public EntityReconciliationAccumulator(int exampleLimit) {
		if (exampleLimit < 1) {
			throw new IllegalArgumentException("exampleLimit must be greater than 0");
		}
		this.exampleLimit = exampleLimit;
	}

	public int retainedObservationCount() {
		return retainedObservations;
	}

	public void beginBatch() {
		batchesAggregated++;
	}

	public void noteCardinality(String parentEntity, String childEntity, String cardinality) {
		if (blank(parentEntity) || blank(childEntity) || blank(cardinality)) {
			return;
		}
		edge(parentEntity, childEntity).cardinality = cardinality.trim();
	}

	public void targetCount(long count) {
		targetCountState = TargetCountState.AVAILABLE;
		targetCount = count;
	}

	public void targetCountUnavailable() {
		targetCountState = TargetCountState.UNAVAILABLE;
		targetCount = null;
	}

	public void markTargetResultsKnown() {
		if (targetResults != TargetResultState.UNAVAILABLE) {
			targetResults = TargetResultState.KNOWN;
		}
	}

	public void markTargetResultsUnavailable() {
		targetResults = TargetResultState.UNAVAILABLE;
	}

	public void observe(EntityObservation observation) {
		requireEntity(observation.entityName());
		markTargetResultsKnown();
		EntityCounter counter = entities.computeIfAbsent(observation.entityName(), name -> new EntityCounter());
		counter.source++;
		if (observation.planned()) {
			counter.planned++;
		}
		if (observation.processed()) {
			counter.processed++;
		} else {
			example(observation.entityName(), observation.sourceIdentity(), "SOURCE_NOT_PROCESSED", null);
		}
		switch (observation.outcome() == null ? EntityOutcome.SUCCESS : observation.outcome()) {
			case SUCCESS -> {
				if (observation.processed()) {
					applySuccess(counter, observation.entityName(), observation.sourceIdentity(), observation.targetIdentity());
				}
			}
			case FAILURE -> {
				counter.failure++;
				example(observation.entityName(), observation.sourceIdentity(), "PROCESSING_FAILED", null);
			}
			case IDENTITY_UNRESOLVED -> {
				counter.identityUnresolved++;
				example(observation.entityName(), observation.sourceIdentity(), "TARGET_IDENTITY_NOT_FOUND", null);
			}
			case LOAD_FAILURE -> {
				counter.loadFailure++;
				example(observation.entityName(), observation.sourceIdentity(), "LOAD_FAILURE", null);
			}
		}
		for (RelationshipFact fact : observation.relationships()) {
			expectAndJudge(observation.entityName(), observation.sourceIdentity(), fact);
		}
	}

	public void notePlanned(String entityName, List<String> parentEntities) {
		notePlanned(entityName, null, parentEntities);
	}

	public void notePlanned(String entityName, String sourceIdentity, List<String> parentEntities) {
		if (blank(entityName)) {
			return;
		}
		if (!blank(sourceIdentity)) {
			plannedIdentities.add(plannedKey(entityName, sourceIdentity));
		}
		EntityCounter counter = entities.computeIfAbsent(entityName, name -> new EntityCounter());
		counter.source++;
		counter.planned++;
		counter.processed++;
		if (parentEntities == null) {
			return;
		}
		for (String parentEntity : parentEntities) {
			if (!blank(parentEntity)) {
				edge(parentEntity, entityName).expected++;
			}
		}
	}

	public boolean wasPlanned(String entityName, String sourceIdentity) {
		return !blank(entityName) && !blank(sourceIdentity)
				&& plannedIdentities.contains(plannedKey(entityName, sourceIdentity));
	}

	public void noteUnplannedSource(String entityName, String sourceIdentity, String reason) {
		if (blank(entityName)) {
			return;
		}
		entities.computeIfAbsent(entityName, name -> new EntityCounter()).source++;
		example(entityName, sourceIdentity, reason == null ? "SOURCE_NOT_PROCESSED" : reason, null);
	}

	public void noteExecutionSuccess(
			String entityName,
			String sourceIdentity,
			String targetIdentity,
			List<RelationshipFact> links) {
		if (blank(entityName) || targetResults == TargetResultState.UNAVAILABLE) {
			return;
		}
		markTargetResultsKnown();
		EntityCounter counter = entities.computeIfAbsent(entityName, name -> new EntityCounter());
		applySuccess(counter, entityName, sourceIdentity, targetIdentity);
		if (links == null) {
			return;
		}
		for (RelationshipFact fact : links) {
			judge(entityName, sourceIdentity, fact);
		}
	}

	public void noteFailure(String entityName, String sourceIdentity, EntityOutcome outcome, String reason) {
		if (blank(entityName)) {
			return;
		}
		EntityCounter counter = entities.computeIfAbsent(entityName, name -> new EntityCounter());
		if (outcome == EntityOutcome.IDENTITY_UNRESOLVED) {
			counter.identityUnresolved++;
			example(entityName, sourceIdentity, "TARGET_IDENTITY_NOT_FOUND", null);
		} else if (outcome == EntityOutcome.LOAD_FAILURE) {
			counter.loadFailure++;
			example(entityName, sourceIdentity, reason == null ? "LOAD_FAILURE" : reason, null);
		} else {
			counter.failure++;
			example(entityName, sourceIdentity, reason == null ? "PROCESSING_FAILED" : reason, null);
		}
	}

	public void noteRelationshipIssue(String parentEntity, String childEntity, String childSourceIdentity, String reason) {
		if (blank(childEntity)) {
			return;
		}
		String parent = blank(parentEntity) ? "" : parentEntity;
		EdgeCounter edge = edge(parent, childEntity);
		edge.issues++;
		if (edge.reason == null) {
			edge.reason = reason;
		}
		example(childEntity, childSourceIdentity, reason == null ? "REFERENCE_MISSING" : reason, parentEntity);
	}

	public EntityReconciliationResult finish() {
		boolean resultsKnown = targetResults == TargetResultState.KNOWN;
		boolean resultsUnavailable = targetResults == TargetResultState.UNAVAILABLE
				|| (targetResults == TargetResultState.UNKNOWN && processedCount() > 0);
		boolean discrepancy = false;
		long source = 0;
		long planned = 0;
		long processed = 0;
		long success = 0;
		long failure = 0;
		long identityUnresolved = 0;
		long loadFailure = 0;
		long identityResolved = 0;
		long absoluteDifference = 0;
		long unaccounted = 0;
		Map<String, Object> entityDetails = new LinkedHashMap<>();
		for (Map.Entry<String, EntityCounter> entry : entities.entrySet()) {
			EntityCounter counter = entry.getValue();
			source += counter.source;
			planned += counter.planned;
			processed += counter.processed;
			success += counter.success;
			failure += counter.failure;
			identityUnresolved += counter.identityUnresolved;
			loadFailure += counter.loadFailure;
			identityResolved += counter.identityResolved;
			long difference = counter.source - counter.processed;
			absoluteDifference += Math.abs(difference);
			if (difference != 0 || counter.failure > 0 || counter.identityUnresolved > 0 || counter.loadFailure > 0) {
				discrepancy = true;
			}
			if (resultsKnown) {
				long gap = counter.processed - counter.success - counter.failure - counter.identityUnresolved
						- counter.loadFailure;
				unaccounted += gap;
				if (gap != 0) {
					discrepancy = true;
				}
			}
			entityDetails.put(entry.getKey(), entityMap(counter, difference, resultsKnown, resultsUnavailable));
		}
		long relationshipFailures = 0;
		boolean relationshipsUnconfirmed = false;
		List<Map<String, Object>> relationshipDetails = new ArrayList<>();
		for (Map.Entry<EdgeKey, EdgeCounter> entry : relationships.entrySet()) {
			EdgeCounter edge = entry.getValue();
			if (edge.expected == 0 && edge.resolved == 0 && edge.issues == 0) {
				continue;
			}
			long unresolved = unresolvedCount(edge, resultsKnown);
			if (!resultsKnown && resultsUnavailable && edge.expected > 0 && edge.issues == 0) {
				relationshipsUnconfirmed = true;
			}
			if (unresolved > 0 || (resultsKnown && edge.resolved > edge.expected)) {
				discrepancy = true;
			}
			relationshipFailures += unresolved;
			relationshipDetails.add(relationshipMap(entry.getKey(), edge, unresolved, resultsKnown, resultsUnavailable));
		}
		ReconciliationStatus status;
		if (discrepancy) {
			status = ReconciliationStatus.FAILED;
		} else if (targetCountState == TargetCountState.UNAVAILABLE || resultsUnavailable || relationshipsUnconfirmed) {
			status = ReconciliationStatus.PASSED_WITH_WARNINGS;
		} else {
			status = ReconciliationStatus.PASSED;
		}
		long failedEntities = failure + identityUnresolved + loadFailure;
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("modelType", "ENTITY_RELATIONSHIP");
		details.put("views", List.of("ENTITY_COUNT", "ENTITY_RESULT", "RELATIONSHIP", "OVERALL"));
		details.put("entityStatus", status.name());
		details.put("exampleLimit", exampleLimit);
		details.put("examplesOmitted", examplesOmitted);
		details.put("batchesAggregated", batchesAggregated);
		if (targetCountState != TargetCountState.UNSPECIFIED) {
			details.put("targetCountAvailable", targetCountState == TargetCountState.AVAILABLE);
		}
		if (targetCountState == TargetCountState.AVAILABLE) {
			details.put("targetCount", targetCount);
		}
		details.put("overall", overallMap(
				source,
				planned,
				processed,
				success,
				failedEntities,
				identityUnresolved,
				loadFailure,
				relationshipFailures,
				resultsKnown,
				resultsUnavailable));
		details.put("entities", entityDetails);
		details.put("relationships", relationshipDetails);
		details.put("examples", List.copyOf(examples));
		Long reportedSuccess = resultsKnown ? success : null;
		return new EntityReconciliationResult(
				status,
				source,
				processed,
				reportedSuccess,
				failedEntities,
				absoluteDifference,
				retainedObservations,
				details);
	}

	private void applySuccess(EntityCounter counter, String entityName, String sourceIdentity, String targetIdentity) {
		if (blank(sourceIdentity)) {
			counter.failure++;
			example(entityName, sourceIdentity, "SOURCE_NOT_PROCESSED", null);
			return;
		}
		counter.success++;
		if (!blank(targetIdentity)) {
			counter.identityResolved++;
		}
	}

	private void expectAndJudge(String childEntity, String childSourceIdentity, RelationshipFact fact) {
		if (fact == null || blank(fact.parentEntity())) {
			return;
		}
		EdgeCounter edge = edge(fact.parentEntity(), childEntity);
		edge.expected++;
		if (fact.cardinality() != null && edge.cardinality == null) {
			edge.cardinality = fact.cardinality();
		}
		judge(childEntity, childSourceIdentity, fact);
	}

	private void judge(String childEntity, String childSourceIdentity, RelationshipFact fact) {
		if (fact == null || blank(fact.parentEntity()) || targetResults == TargetResultState.UNAVAILABLE) {
			return;
		}
		EdgeCounter edge = edge(fact.parentEntity(), childEntity);
		if (fact.cardinality() != null && edge.cardinality == null) {
			edge.cardinality = fact.cardinality();
		}
		RelationshipCondition condition = classify(fact);
		if (condition == RelationshipCondition.RESOLVED) {
			edge.resolved++;
			return;
		}
		edge.issues++;
		if (edge.reason == null) {
			edge.reason = condition.name();
		}
		example(childEntity, childSourceIdentity, condition.name(), fact.parentEntity());
	}

	private static RelationshipCondition classify(RelationshipFact fact) {
		if (fact.condition() != null && fact.condition() != RelationshipCondition.RESOLVED) {
			return fact.condition();
		}
		if (blank(fact.parentSourceIdentity())) {
			return RelationshipCondition.REFERENCE_MISSING;
		}
		if (!blank(fact.parentTargetIdentity()) && fact.parentTargetIdentity().equals(fact.parentSourceIdentity())) {
			return RelationshipCondition.INCONSISTENT;
		}
		return RelationshipCondition.RESOLVED;
	}

	private Map<String, Object> entityMap(
			EntityCounter counter,
			long difference,
			boolean resultsKnown,
			boolean resultsUnavailable) {
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("sourceCount", counter.source);
		values.put("plannedCount", counter.planned);
		values.put("processedCount", counter.processed);
		values.put("successCount", metric(counter.success, resultsKnown, resultsUnavailable));
		values.put("failureCount", counter.failure);
		values.put("identityUnresolvedCount", counter.identityUnresolved);
		values.put("loadFailureCount", counter.loadFailure);
		values.put("identityResolvedCount", metric(counter.identityResolved, resultsKnown, resultsUnavailable));
		values.put("extractedCount", UNAVAILABLE);
		values.put("difference", difference);
		return values;
	}

	private Map<String, Object> relationshipMap(
			EdgeKey key,
			EdgeCounter edge,
			long unresolved,
			boolean resultsKnown,
			boolean resultsUnavailable) {
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("parentEntity", key.parent);
		values.put("childEntity", key.child);
		if (edge.cardinality != null) {
			values.put("cardinality", edge.cardinality);
		}
		long displayedExpected = edge.expected > 0 ? edge.expected : Math.max(edge.resolved, unresolved);
		values.put("expected", displayedExpected);
		values.put("resolved", resultsKnown ? edge.resolved : UNAVAILABLE);
		values.put("unresolved", unresolved);
		ReconciliationStatus edgeStatus;
		if (unresolved > 0 || (resultsKnown && edge.resolved > edge.expected)) {
			edgeStatus = ReconciliationStatus.FAILED;
		} else if (resultsUnavailable && edge.expected > 0) {
			edgeStatus = ReconciliationStatus.PASSED_WITH_WARNINGS;
		} else {
			edgeStatus = ReconciliationStatus.PASSED;
		}
		values.put("status", edgeStatus.name());
		if (edge.reason != null) {
			values.put("reason", edge.reason);
		}
		return values;
	}

	private Map<String, Object> overallMap(
			long source,
			long planned,
			long processed,
			long success,
			long failedEntities,
			long identityUnresolved,
			long loadFailure,
			long relationshipFailures,
			boolean resultsKnown,
			boolean resultsUnavailable) {
		Map<String, Object> overall = new LinkedHashMap<>();
		overall.put("sourceEntityCount", source);
		overall.put("plannedEntityCount", planned);
		overall.put("processedEntityCount", processed);
		overall.put("successfulEntityCount", metric(success, resultsKnown, resultsUnavailable));
		overall.put("failedEntityCount", failedEntities);
		overall.put("unresolvedIdentityCount", identityUnresolved);
		overall.put("loadFailureCount", loadFailure);
		overall.put("relationshipFailureCount", relationshipFailures);
		overall.put("extractedEntityCount", UNAVAILABLE);
		return overall;
	}

	private static long unresolvedCount(EdgeCounter edge, boolean resultsKnown) {
		long unresolved = edge.issues;
		if (!resultsKnown) {
			return unresolved;
		}
		unresolved += Math.max(0, edge.expected - edge.resolved - edge.issues);
		if (edge.resolved > edge.expected) {
			unresolved += edge.resolved - edge.expected;
		}
		return unresolved;
	}

	private static Object metric(long value, boolean resultsKnown, boolean resultsUnavailable) {
		if (resultsKnown) {
			return value;
		}
		if (resultsUnavailable) {
			return UNAVAILABLE;
		}
		return value;
	}

	private void example(String entityName, String sourceIdentity, String reason, String parentEntity) {
		if (examples.size() >= exampleLimit) {
			examplesOmitted++;
			return;
		}
		Map<String, Object> example = new LinkedHashMap<>();
		example.put("entity", entityName);
		example.put("sourceIdentity", sourceIdentity == null ? "" : sourceIdentity);
		example.put("reason", reason);
		example.put("resolvedTargetIdentity", UNRESOLVED_TARGET);
		if (!blank(parentEntity)) {
			example.put("parentEntity", parentEntity);
		}
		examples.add(example);
	}

	private EdgeCounter edge(String parentEntity, String childEntity) {
		return relationships.computeIfAbsent(new EdgeKey(parentEntity, childEntity), key -> new EdgeCounter());
	}

	private long processedCount() {
		long processed = 0;
		for (EntityCounter counter : entities.values()) {
			processed += counter.processed;
		}
		return processed;
	}

	private static void requireEntity(String entityName) {
		if (blank(entityName)) {
			throw new IllegalArgumentException("entityName is required");
		}
	}

	private static String plannedKey(String entityName, String sourceIdentity) {
		return entityName + "\u0000" + sourceIdentity;
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}

	private enum TargetResultState {
		UNKNOWN,
		KNOWN,
		UNAVAILABLE
	}

	private enum TargetCountState {
		UNSPECIFIED,
		AVAILABLE,
		UNAVAILABLE
	}

	private record EdgeKey(String parent, String child) implements Comparable<EdgeKey> {
		@Override
		public int compareTo(EdgeKey other) {
			int compared = parent.compareTo(other.parent);
			return compared != 0 ? compared : child.compareTo(other.child);
		}
	}

	private static final class EntityCounter {
		private long source;
		private long planned;
		private long processed;
		private long success;
		private long failure;
		private long identityUnresolved;
		private long loadFailure;
		private long identityResolved;
	}

	private static final class EdgeCounter {
		private long expected;
		private long resolved;
		private long issues;
		private String cardinality;
		private String reason;
	}
}
