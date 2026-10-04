package com.etp.migration.identity;

import com.etp.migration.mapping.EntityDefinition;
import com.etp.migration.mapping.EntityRelationshipGraph;
import com.etp.migration.mapping.EntityRelationshipMapping;
import com.etp.migration.mapping.RelationshipDefinition;
import com.etp.migration.planning.ExecutionBatchPlan;
import com.etp.migration.planning.ExecutionWorkItem;
import com.etp.migration.planning.WorkItemParent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Resolves source identities for one execution batch. Lookups are grouped by entity and split into chunks.
 * Results are discarded when the call returns. There is no application-wide identity cache and no target write.
 */
public class IdentityResolutionService {

	public IdentityResolutionResult resolve(
			EntityRelationshipMapping mapping,
			ExecutionBatchPlan batch,
			TargetIdentityLookup lookup,
			int chunkSize) {
		if (mapping == null) {
			throw new IllegalArgumentException("mapping is required");
		}
		if (batch == null) {
			throw new IllegalArgumentException("batch is required");
		}
		if (lookup == null) {
			throw new IllegalArgumentException("lookup is required");
		}
		if (chunkSize < 1) {
			throw new IllegalArgumentException("chunkSize must be greater than 0");
		}
		long started = com.etp.migration.processing.PipelineTiming.start();
		try {
			return resolveMeasured(mapping, batch, lookup, chunkSize);
		} finally {
			com.etp.migration.processing.PipelineTiming.stop(
					com.etp.migration.processing.PipelineTiming.Stage.IDENTITY_RESOLUTION,
					started);
		}
	}

	private IdentityResolutionResult resolveMeasured(
			EntityRelationshipMapping mapping,
			ExecutionBatchPlan batch,
			TargetIdentityLookup lookup,
			int chunkSize) {
		if (batch.workItems().isEmpty()) {
			return new IdentityResolutionResult(List.of(), List.of());
		}
		EntityRelationshipGraph graph = EntityRelationshipGraph.from(mapping);
		Map<String, Integer> order = dependencyOrder(graph);
		Map<String, LinkedHashSet<String>> needed = new LinkedHashMap<>();
		List<ExecutionWorkItem> unique = new ArrayList<>();
		Set<EntityIdentity> seen = new HashSet<>();
		for (ExecutionWorkItem item : batch.workItems()) {
			EntityIdentity identity = identity(item.entityName(), item.sourceIdentity(), order);
			if (seen.add(identity)) {
				unique.add(item);
			}
			needed.computeIfAbsent(identity.entityName(), ignored -> new LinkedHashSet<>()).add(identity.sourceIdentity());
			for (WorkItemParent parent : item.parents()) {
				EntityIdentity parentIdentity = identity(parent.entityName(), parent.sourceIdentity(), order);
				needed.computeIfAbsent(parentIdentity.entityName(), ignored -> new LinkedHashSet<>())
						.add(parentIdentity.sourceIdentity());
			}
		}
		List<String> entityOrder = needed.keySet().stream()
				.sorted(Comparator.comparingInt((String name) -> order.get(name)).thenComparing(Comparator.naturalOrder()))
				.toList();
		Map<EntityIdentity, String> found = new HashMap<>();
		Map<EntityIdentity, String> failures = new HashMap<>();
		for (String entityName : entityOrder) {
			List<String> sourceIdentities = new ArrayList<>(needed.get(entityName));
			for (int offset = 0; offset < sourceIdentities.size(); offset += chunkSize) {
				List<String> chunk = List.copyOf(
						sourceIdentities.subList(offset, Math.min(offset + chunkSize, sourceIdentities.size())));
				try {
					com.etp.migration.processing.PipelineTiming.increment("identityLookupCalls");
					Map<String, String> resolved = lookup.lookup(entityName, chunk);
					if (resolved == null) {
						fail(entityName, chunk, failures, "Identity lookup returned no result.");
						continue;
					}
					for (String sourceIdentity : chunk) {
						String targetIdentity = resolved.get(sourceIdentity);
						if (targetIdentity != null && !targetIdentity.isBlank()) {
							found.put(new EntityIdentity(entityName, sourceIdentity), targetIdentity.trim());
						}
					}
				} catch (RuntimeException ex) {
					String message = ex.getMessage() == null || ex.getMessage().isBlank()
							? "Identity lookup failed."
							: ex.getMessage();
					fail(entityName, chunk, failures, message);
				}
			}
		}
		List<ResolvedIdentity> resolved = new ArrayList<>();
		List<UnresolvedIdentity> unresolved = new ArrayList<>();
		for (ExecutionWorkItem item : unique) {
			EntityIdentity identity = new EntityIdentity(item.entityName().trim(), item.sourceIdentity().trim());
			EntityDefinition definition = graph.entity(identity.entityName());
			List<ParentIdentityReference> parents = parents(item, found, failures);
			if (failures.containsKey(identity)) {
				unresolved.add(new UnresolvedIdentity(
						identity,
						IdentityResolutionFailureKind.LOOKUP_FAILED,
						failures.get(identity),
						parents));
				continue;
			}
			String targetIdentity = found.get(identity);
			if (targetIdentity == null) {
				unresolved.add(new UnresolvedIdentity(
						identity,
						IdentityResolutionFailureKind.TARGET_IDENTITY_NOT_FOUND,
						"Target identity for entity '" + identity.entityName() + "' source '"
								+ identity.sourceIdentity() + "' was not found.",
						parents));
				continue;
			}
			resolved.add(new ResolvedIdentity(
					identity,
					targetIdentity,
					definition.targetEntity(),
					definition.sourceKey(),
					definition.targetKey(),
					parents));
		}
		return new IdentityResolutionResult(resolved, unresolved);
	}

	private static void fail(
			String entityName,
			List<String> sourceIdentities,
			Map<EntityIdentity, String> failures,
			String message) {
		for (String sourceIdentity : sourceIdentities) {
			failures.put(new EntityIdentity(entityName, sourceIdentity), message);
		}
	}

	private static List<ParentIdentityReference> parents(
			ExecutionWorkItem item,
			Map<EntityIdentity, String> found,
			Map<EntityIdentity, String> failures) {
		List<ParentIdentityReference> parents = new ArrayList<>();
		Set<EntityIdentity> seen = new HashSet<>();
		for (WorkItemParent parent : item.parents()) {
			EntityIdentity source = new EntityIdentity(parent.entityName().trim(), parent.sourceIdentity().trim());
			if (!seen.add(source)) {
				continue;
			}
			if (failures.containsKey(source) || !found.containsKey(source)) {
				parents.add(new ParentIdentityReference(source, null, ParentIdentityStatus.UNRESOLVED));
				continue;
			}
			parents.add(new ParentIdentityReference(source, found.get(source), ParentIdentityStatus.RESOLVED));
		}
		return List.copyOf(parents);
	}

	private static EntityIdentity identity(String entityName, String sourceIdentity, Map<String, Integer> order) {
		if (entityName == null || entityName.isBlank() || !order.containsKey(entityName.trim())) {
			throw new IdentityResolutionException(
					"Entity '" + entityName + "' is not defined in the entity relationship graph.");
		}
		if (sourceIdentity == null || sourceIdentity.isBlank()) {
			throw new IdentityResolutionException("Entity '" + entityName.trim() + "' is missing a source identity.");
		}
		return new EntityIdentity(entityName.trim(), sourceIdentity.trim());
	}

	private static Map<String, Integer> dependencyOrder(EntityRelationshipGraph graph) {
		Map<String, Integer> indegree = new HashMap<>();
		Map<String, List<String>> children = new HashMap<>();
		for (EntityDefinition entity : graph.entities()) {
			indegree.put(entity.name(), 0);
			children.put(entity.name(), new ArrayList<>());
		}
		for (RelationshipDefinition relationship : graph.relationships()) {
			if (!indegree.containsKey(relationship.parent()) || !indegree.containsKey(relationship.child())) {
				throw new IdentityResolutionException(
						"Relationship from '" + relationship.parent() + "' to '" + relationship.child()
								+ "' is not defined in the entity relationship graph.");
			}
			indegree.merge(relationship.child(), 1, Integer::sum);
			children.get(relationship.parent()).add(relationship.child());
		}
		TreeSet<String> ready = new TreeSet<>();
		for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
			if (entry.getValue() == 0) {
				ready.add(entry.getKey());
			}
		}
		Map<String, Integer> ranks = new HashMap<>();
		int rank = 0;
		while (!ready.isEmpty()) {
			String name = ready.pollFirst();
			ranks.put(name, rank++);
			for (String child : children.getOrDefault(name, List.of())) {
				int remaining = indegree.merge(child, -1, Integer::sum);
				if (remaining == 0) {
					ready.add(child);
				}
			}
		}
		if (ranks.size() != indegree.size()) {
			throw new IdentityResolutionException("Entity relationship graph contains a cycle");
		}
		return ranks;
	}
}
