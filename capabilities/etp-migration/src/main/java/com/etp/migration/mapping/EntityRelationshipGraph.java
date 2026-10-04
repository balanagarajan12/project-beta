package com.etp.migration.mapping;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Structural view of a validated entity relationship mapping.
 * Traversal is available for later phases. This type does not plan batches or resolve identities.
 */
public final class EntityRelationshipGraph {

	private final EntityRelationshipMapping mapping;
	private final Map<String, EntityDefinition> entitiesByName;
	private final Map<String, List<RelationshipDefinition>> outgoing;

	private EntityRelationshipGraph(
			EntityRelationshipMapping mapping,
			Map<String, EntityDefinition> entitiesByName,
			Map<String, List<RelationshipDefinition>> outgoing) {
		this.mapping = mapping;
		this.entitiesByName = entitiesByName;
		this.outgoing = outgoing;
	}

	public static EntityRelationshipGraph from(EntityRelationshipMapping mapping) {
		Map<String, EntityDefinition> entitiesByName = new LinkedHashMap<>();
		for (EntityDefinition entity : mapping.entities()) {
			entitiesByName.put(entity.name(), entity);
		}
		Map<String, List<RelationshipDefinition>> outgoing = new LinkedHashMap<>();
		for (String name : entitiesByName.keySet()) {
			outgoing.put(name, new ArrayList<>());
		}
		for (RelationshipDefinition relationship : mapping.relationships()) {
			outgoing.computeIfAbsent(relationship.parent(), ignored -> new ArrayList<>()).add(relationship);
		}
		outgoing.replaceAll((name, edges) -> List.copyOf(edges));
		EntityRelationshipGraph graph = new EntityRelationshipGraph(mapping, entitiesByName, outgoing);
		graph.rejectCycles();
		return graph;
	}

	public String rootEntity() {
		return mapping.rootEntity();
	}

	public EntityDefinition entity(String name) {
		return entitiesByName.get(name);
	}

	public List<EntityDefinition> entities() {
		return List.copyOf(entitiesByName.values());
	}

	public List<RelationshipDefinition> relationships() {
		return mapping.relationships();
	}

	public List<RelationshipDefinition> outgoing(String entityName) {
		return outgoing.getOrDefault(entityName, List.of());
	}

	private void rejectCycles() {
		Map<String, Integer> color = new HashMap<>();
		if (entitiesByName.containsKey(mapping.rootEntity())) {
			visit(mapping.rootEntity(), color, new ArrayList<>());
		}
		for (String name : entitiesByName.keySet()) {
			if (color.getOrDefault(name, 0) == 0) {
				visit(name, color, new ArrayList<>());
			}
		}
	}

	private void visit(String name, Map<String, Integer> color, List<String> path) {
		color.put(name, 1);
		path.add(name);
		for (RelationshipDefinition relationship : outgoing.getOrDefault(name, List.of())) {
			String child = relationship.child();
			int state = color.getOrDefault(child, 0);
			if (state == 1) {
				int start = path.indexOf(child);
				List<String> cycle = new ArrayList<>(path.subList(start, path.size()));
				cycle.add(child);
				throw new InvalidEntityRelationshipConfigurationException(
						"Entity relationship graph contains a cycle: " + String.join(" -> ", cycle));
			}
			if (state == 0) {
				visit(child, color, path);
			}
		}
		path.remove(path.size() - 1);
		color.put(name, 2);
	}
}
