package com.etp.migration.execution;

import com.etp.migration.planning.ExecutionBatchPlan;
import com.etp.migration.planning.ExecutionWorkItem;
import com.etp.migration.planning.WorkItemParent;
import com.etp.migration.processing.BatchProcessingException;
import com.etp.migration.processing.MigrationErrorCodes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stores one execution batch in {@code migration_batch.source_locator}.
 * The locator holds identities, not source payloads.
 */
public final class EntityBatchLocator {

	public static final String TYPE = "ENTITY_RELATIONSHIP_BATCH";

	private EntityBatchLocator() {
	}

	public static boolean isEntityBatch(Map<String, Object> locator) {
		return locator != null && TYPE.equals(String.valueOf(locator.get("type")));
	}

	public static Map<String, Object> write(ExecutionBatchPlan plan) {
		List<Map<String, Object>> items = new ArrayList<>();
		for (ExecutionWorkItem item : plan.workItems()) {
			List<Map<String, Object>> parents = new ArrayList<>();
			for (WorkItemParent parent : item.parents()) {
				Map<String, Object> parentMap = new LinkedHashMap<>();
				parentMap.put("entityName", parent.entityName());
				parentMap.put("sourceIdentity", parent.sourceIdentity());
				parents.add(parentMap);
			}
			Map<String, Object> stored = new LinkedHashMap<>();
			stored.put("entityName", item.entityName());
			stored.put("sourceIdentity", item.sourceIdentity());
			stored.put("sourceRecordNumber", item.sourceRecordNumber());
			stored.put("rootEntity", item.rootEntity());
			stored.put("rootIdentity", item.rootIdentity());
			stored.put("parents", parents);
			items.add(stored);
		}
		Map<String, Object> locator = new LinkedHashMap<>();
		locator.put("type", TYPE);
		locator.put("executionBatchNumber", plan.batchNumber());
		locator.put("workItems", items);
		return locator;
	}

	public static int relationshipFailureCount(Map<String, Object> locator) {
		if (locator == null || !(locator.get("relationshipFailureCount") instanceof Number number)) {
			return 0;
		}
		return Math.max(0, number.intValue());
	}

	public static Map<String, Object> withRelationshipFailures(Map<String, Object> locator, int count) {
		Map<String, Object> copy = new LinkedHashMap<>();
		if (locator != null) {
			copy.putAll(locator);
		}
		copy.put("relationshipFailureCount", count);
		return copy;
	}

	public static ExecutionBatchPlan read(Map<String, Object> locator) {
		if (!isEntityBatch(locator)) {
			throw new BatchProcessingException(
					MigrationErrorCodes.SOURCE_LOCATOR_INVALID,
					"Entity batch locator is missing");
		}
		Object rawItems = locator.get("workItems");
		if (!(rawItems instanceof List<?> items)) {
			throw new BatchProcessingException(
					MigrationErrorCodes.SOURCE_LOCATOR_INVALID,
					"Entity batch locator does not contain work items");
		}
		List<ExecutionWorkItem> workItems = new ArrayList<>();
		for (Object raw : items) {
			if (!(raw instanceof Map<?, ?> item)) {
				throw new BatchProcessingException(
						MigrationErrorCodes.SOURCE_LOCATOR_INVALID,
						"Entity batch work item is invalid");
			}
			workItems.add(new ExecutionWorkItem(
					text(item.get("entityName")),
					text(item.get("sourceIdentity")),
					number(item.get("sourceRecordNumber")),
					text(item.get("rootEntity")),
					text(item.get("rootIdentity")),
					parents(item.get("parents"))));
		}
		return new ExecutionBatchPlan(number(locator.get("executionBatchNumber")).intValue(), workItems);
	}

	private static List<WorkItemParent> parents(Object raw) {
		if (!(raw instanceof List<?> items)) {
			return List.of();
		}
		List<WorkItemParent> parents = new ArrayList<>();
		for (Object item : items) {
			if (item instanceof Map<?, ?> parent) {
				parents.add(new WorkItemParent(text(parent.get("entityName")), text(parent.get("sourceIdentity"))));
			}
		}
		return parents;
	}

	private static String text(Object value) {
		return value == null ? null : value.toString();
	}

	private static Long number(Object value) {
		if (value instanceof Number number) {
			return number.longValue();
		}
		throw new BatchProcessingException(
				MigrationErrorCodes.SOURCE_LOCATOR_INVALID,
				"Entity batch locator has an invalid number");
	}
}
