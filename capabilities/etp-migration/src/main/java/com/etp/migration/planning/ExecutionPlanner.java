package com.etp.migration.planning;

import com.etp.migration.mapping.EntityDefinition;
import com.etp.migration.mapping.EntityRelationshipGraph;
import com.etp.migration.mapping.EntityRelationshipMapping;
import com.etp.migration.mapping.InvalidEntityRelationshipConfigurationException;
import com.etp.migration.mapping.RelationshipDefinition;
import com.etp.migration.scope.LogicalMigrationScope;
import com.etp.migration.scope.ScopedEntity;
import com.etp.migration.scope.ScopedRelationship;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.springframework.stereotype.Component;

/**
 * Turns a stream of logical scopes into a stream of execution batches.
 * Entity types are ordered by the configured relationship graph. Within a type, scope membership order is kept.
 * A child is never emitted before its parent. One scope may fill many batches, and one batch may contain several scopes.
 * Only the current scope and the current batch are held. Source field payloads are not read.
 * This planner does not persist {@code migration_batch} and is not used by flat execution.
 */
@Component
public class ExecutionPlanner {

	public Stream<ExecutionBatchPlan> plan(
			EntityRelationshipMapping mapping,
			Stream<LogicalMigrationScope> scopes,
			int batchSize) {
		if (mapping == null) {
			throw new IllegalArgumentException("mapping is required");
		}
		if (scopes == null) {
			throw new IllegalArgumentException("scopes are required");
		}
		if (batchSize < 1) {
			throw new IllegalArgumentException("batchSize must be greater than 0");
		}
		EntityRelationshipGraph graph = EntityRelationshipGraph.from(mapping);
		Map<String, Integer> order = dependencyOrder(graph);
		Set<String> edges = edges(graph);
		Iterator<ExecutionBatchPlan> batches = new BatchCursor(graph.rootEntity(), order, edges, scopes.iterator(), batchSize);
		return StreamSupport.stream(
						Spliterators.spliteratorUnknownSize(batches, Spliterator.ORDERED),
						false)
				.onClose(scopes::close);
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
				throw new InvalidExecutionPlanException(
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
		Map<String, Integer> order = new HashMap<>();
		int rank = 0;
		while (!ready.isEmpty()) {
			String name = ready.pollFirst();
			order.put(name, rank++);
			for (String child : children.getOrDefault(name, List.of())) {
				int remaining = indegree.merge(child, -1, Integer::sum);
				if (remaining == 0) {
					ready.add(child);
				}
			}
		}
		if (order.size() != indegree.size()) {
			throw new InvalidEntityRelationshipConfigurationException(
					"Entity relationship graph contains a cycle");
		}
		return order;
	}

	private static Set<String> edges(EntityRelationshipGraph graph) {
		Set<String> edges = new HashSet<>();
		for (RelationshipDefinition relationship : graph.relationships()) {
			edges.add(edge(relationship.parent(), relationship.child()));
		}
		return edges;
	}

	private static String edge(String parent, String child) {
		return parent + "\0" + child;
	}

	private static final class BatchCursor implements Iterator<ExecutionBatchPlan> {
		private final String rootEntity;
		private final Map<String, Integer> order;
		private final Set<String> edges;
		private final Iterator<LogicalMigrationScope> scopes;
		private final int batchSize;
		private Iterator<ExecutionWorkItem> current = Collections.emptyIterator();
		private ExecutionWorkItem lookahead;
		private int batchNumber = 1;
		private boolean finished;

		private BatchCursor(
				String rootEntity,
				Map<String, Integer> order,
				Set<String> edges,
				Iterator<LogicalMigrationScope> scopes,
				int batchSize) {
			this.rootEntity = rootEntity;
			this.order = order;
			this.edges = edges;
			this.scopes = scopes;
			this.batchSize = batchSize;
		}

		@Override
		public boolean hasNext() {
			if (finished) {
				return false;
			}
			if (lookahead != null || current.hasNext() || scopes.hasNext()) {
				return pull() != null;
			}
			finished = true;
			return false;
		}

		@Override
		public ExecutionBatchPlan next() {
			if (!hasNext()) {
				throw new NoSuchElementException();
			}
			List<ExecutionWorkItem> items = new ArrayList<>(batchSize);
			while (items.size() < batchSize && pull() != null) {
				items.add(lookahead);
				lookahead = null;
			}
			if (items.isEmpty()) {
				throw new NoSuchElementException();
			}
			return new ExecutionBatchPlan(batchNumber++, List.copyOf(items));
		}

		private ExecutionWorkItem pull() {
			if (lookahead != null) {
				return lookahead;
			}
			while (!current.hasNext()) {
				if (!scopes.hasNext()) {
					return null;
				}
				current = new ScopeItems(validate(scopes.next()), order);
			}
			lookahead = current.next();
			return lookahead;
		}

		private LogicalMigrationScope validate(LogicalMigrationScope scope) {
			if (scope == null) {
				throw new InvalidExecutionPlanException("scope is required");
			}
			if (!rootEntity.equals(scope.rootEntity())) {
				throw new InvalidExecutionPlanException(
						"Scope root entity '" + scope.rootEntity() + "' does not match configured root entity '"
								+ rootEntity + "'");
			}
			Set<Ref> members = new HashSet<>();
			boolean rootPresent = false;
			for (ScopedEntity entity : scope.entities()) {
				if (entity == null || entity.entityName() == null || !order.containsKey(entity.entityName())) {
					String name = entity == null ? "" : entity.entityName();
					throw new InvalidExecutionPlanException(
							"Entity '" + name + "' is not defined in the entity relationship graph.");
				}
				String identity = identity(entity.sourceIdentity());
				if (identity == null) {
					throw new InvalidExecutionPlanException(
							"Entity '" + entity.entityName() + "' is missing a source identity.");
				}
				members.add(new Ref(entity.entityName(), identity));
				if (rootEntity.equals(entity.entityName()) && identity.equals(identity(scope.rootIdentity()))) {
					rootPresent = true;
				}
			}
			if (!rootPresent) {
				throw new InvalidExecutionPlanException(
						"Scope rooted at '" + scope.rootIdentity() + "' does not contain the root entity.");
			}
			for (ScopedRelationship relationship : scope.relationships()) {
				if (relationship == null
						|| !edges.contains(edge(relationship.parentEntity(), relationship.childEntity()))) {
					String parent = relationship == null ? "" : relationship.parentEntity();
					String child = relationship == null ? "" : relationship.childEntity();
					throw new InvalidExecutionPlanException(
							"Relationship from '" + parent + "' to '" + child
									+ "' is not defined in the entity relationship graph.");
				}
				Ref parent = new Ref(relationship.parentEntity(), identity(relationship.parentIdentity()));
				Ref child = new Ref(relationship.childEntity(), identity(relationship.childIdentity()));
				if (!members.contains(parent) || !members.contains(child)) {
					throw new InvalidExecutionPlanException(
							"Relationship from '" + relationship.parentEntity() + "' to '" + relationship.childEntity()
									+ "' references an entity that is not in the scope.");
				}
			}
			return scope;
		}

		private static String identity(String value) {
			if (value == null || value.isBlank()) {
				return null;
			}
			return value.trim();
		}
	}

	private static final class ScopeItems implements Iterator<ExecutionWorkItem> {
		private final LogicalMigrationScope scope;
		private final List<String> types;
		private final Map<Ref, List<WorkItemParent>> parents;
		private final Set<Ref> emitted = new HashSet<>();
		private int typeCursor;
		private int entityCursor;
		private ExecutionWorkItem lookahead;

		private ScopeItems(LogicalMigrationScope scope, Map<String, Integer> order) {
			this.scope = scope;
			this.types = order.entrySet().stream()
					.sorted(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry::getKey))
					.map(Map.Entry::getKey)
					.toList();
			this.parents = parents(scope, order);
		}

		@Override
		public boolean hasNext() {
			return pull() != null;
		}

		@Override
		public ExecutionWorkItem next() {
			if (!hasNext()) {
				throw new NoSuchElementException();
			}
			ExecutionWorkItem item = lookahead;
			lookahead = null;
			return item;
		}

		private ExecutionWorkItem pull() {
			if (lookahead != null) {
				return lookahead;
			}
			List<ScopedEntity> entities = scope.entities();
			while (typeCursor < types.size()) {
				String type = types.get(typeCursor);
				while (entityCursor < entities.size()) {
					ScopedEntity entity = entities.get(entityCursor++);
					if (entity == null || !type.equals(entity.entityName())) {
						continue;
					}
					String sourceIdentity = entity.sourceIdentity() == null ? "" : entity.sourceIdentity().trim();
					Ref ref = new Ref(entity.entityName(), sourceIdentity);
					if (!emitted.add(ref)) {
						continue;
					}
					lookahead = new ExecutionWorkItem(
							entity.entityName(),
							sourceIdentity,
							entity.sourceRecordNumber(),
							scope.rootEntity(),
							scope.rootIdentity() == null ? null : scope.rootIdentity().trim(),
							parents.getOrDefault(ref, List.of()));
					return lookahead;
				}
				typeCursor++;
				entityCursor = 0;
			}
			return null;
		}

		private static Map<Ref, List<WorkItemParent>> parents(LogicalMigrationScope scope, Map<String, Integer> order) {
			Map<Ref, List<WorkItemParent>> parents = new HashMap<>();
			for (ScopedRelationship relationship : scope.relationships()) {
				Ref child = new Ref(relationship.childEntity(), relationship.childIdentity().trim());
				WorkItemParent parent = new WorkItemParent(
						relationship.parentEntity(),
						relationship.parentIdentity().trim());
				List<WorkItemParent> values = parents.computeIfAbsent(child, ignored -> new ArrayList<>());
				if (!values.contains(parent)) {
					values.add(parent);
				}
			}
			Comparator<WorkItemParent> comparator = Comparator
					.comparingInt((WorkItemParent parent) -> order.getOrDefault(parent.entityName(), Integer.MAX_VALUE))
					.thenComparing(WorkItemParent::sourceIdentity);
			for (List<WorkItemParent> values : parents.values()) {
				values.sort(comparator);
			}
			return parents;
		}
	}

	private record Ref(String entityName, String sourceIdentity) {
	}
}
