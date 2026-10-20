package com.etp.migration.mapping;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Parent-before-child order derived from relationship edges.
 * Independent entities are ordered by name so the result does not depend on declaration order.
 */
public final class EntityLoadOrder {

	private EntityLoadOrder() {
	}

	public record Dependency(String parent, String child) {
	}

	public static List<String> parentsFirst(Collection<String> entities, List<Dependency> dependencies) {
		LinkedHashSet<String> names = new LinkedHashSet<>();
		if (entities != null) {
			for (String entity : entities) {
				if (entity != null && !entity.isBlank()) {
					names.add(entity.trim());
				}
			}
		}
		List<Dependency> edges = new ArrayList<>();
		Set<String> seenEdges = new LinkedHashSet<>();
		if (dependencies != null) {
			for (Dependency dependency : dependencies) {
				if (dependency == null || dependency.parent() == null || dependency.child() == null) {
					continue;
				}
				String parent = dependency.parent().trim();
				String child = dependency.child().trim();
				if (parent.isEmpty() || child.isEmpty()) {
					continue;
				}
				names.add(parent);
				names.add(child);
				if (seenEdges.add(parent + "\u0000" + child)) {
					edges.add(new Dependency(parent, child));
				}
			}
		}
		Map<String, Integer> indegree = new HashMap<>();
		Map<String, List<String>> children = new HashMap<>();
		for (String name : names) {
			indegree.put(name, 0);
			children.put(name, new ArrayList<>());
		}
		for (Dependency edge : edges) {
			indegree.merge(edge.child(), 1, Integer::sum);
			children.get(edge.parent()).add(edge.child());
		}
		TreeSet<String> ready = new TreeSet<>();
		for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
			if (entry.getValue() == 0) {
				ready.add(entry.getKey());
			}
		}
		List<String> order = new ArrayList<>();
		while (!ready.isEmpty()) {
			String name = ready.pollFirst();
			order.add(name);
			for (String child : children.getOrDefault(name, List.of())) {
				int remaining = indegree.merge(child, -1, Integer::sum);
				if (remaining == 0) {
					ready.add(child);
				}
			}
		}
		if (order.size() != indegree.size()) {
			throw new InvalidEntityRelationshipConfigurationException(cycleMessage(names, edges));
		}
		return List.copyOf(order);
	}

	private static String cycleMessage(Set<String> names, List<Dependency> edges) {
		Map<String, List<String>> outgoing = new HashMap<>();
		for (String name : names) {
			outgoing.put(name, new ArrayList<>());
		}
		for (Dependency edge : edges) {
			outgoing.get(edge.parent()).add(edge.child());
		}
		Map<String, Integer> color = new HashMap<>();
		for (String name : names) {
			if (color.getOrDefault(name, 0) == 0) {
				String cycle = visit(name, outgoing, color, new ArrayList<>());
				if (cycle != null) {
					return cycle;
				}
			}
		}
		return "Entity relationship graph contains a cycle";
	}

	private static String visit(
			String name,
			Map<String, List<String>> outgoing,
			Map<String, Integer> color,
			List<String> path) {
		color.put(name, 1);
		path.add(name);
		for (String child : outgoing.getOrDefault(name, List.of())) {
			int state = color.getOrDefault(child, 0);
			if (state == 1) {
				int start = path.indexOf(child);
				List<String> cycle = new ArrayList<>(path.subList(start, path.size()));
				cycle.add(child);
				return "Entity relationship graph contains a cycle: " + String.join(" -> ", cycle);
			}
			if (state == 0) {
				String cycle = visit(child, outgoing, color, path);
				if (cycle != null) {
					return cycle;
				}
			}
		}
		path.remove(path.size() - 1);
		color.put(name, 2);
		return null;
	}
}
