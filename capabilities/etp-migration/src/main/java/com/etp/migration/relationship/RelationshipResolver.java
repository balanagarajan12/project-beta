package com.etp.migration.relationship;

import com.etp.migration.extraction.EntityRecord;
import com.etp.migration.mapping.EntityDefinition;
import com.etp.migration.mapping.EntityRelationshipMapping;
import com.etp.migration.mapping.RelationshipCardinality;
import com.etp.migration.mapping.RelationshipDefinition;
import com.etp.migration.mapping.RelationshipKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Associates entity records using configured relationship keys.
 * The lookup index stores parent identity and source record number, not the entity payload.
 * It is discarded when {@link #resolve} returns. It is not a target identity store.
 */
@Component
public class RelationshipResolver {

	public RelationshipResolution resolve(EntityRelationshipMapping mapping, EntityRecordSource source) {
		if (mapping == null) {
			throw new IllegalArgumentException("mapping is required");
		}
		if (source == null) {
			throw new IllegalArgumentException("source is required");
		}
		mapping.graph();
		Session session = new Session(mapping);
		for (String entityName : involved(mapping, true)) {
			consume(source, entityName, session::index);
		}
		int indexedParentCount = session.indexedParentCount();
		for (String entityName : involved(mapping, false)) {
			consume(source, entityName, session::resolve);
		}
		session.recordFootprint(indexedParentCount);
		return new RelationshipResolution(session.relationshipsOut, session.failures, indexedParentCount);
	}

	/**
	 * Indexes every parent in one source pass, then resolves every child in a second pass.
	 * A single-file source would otherwise be opened once per entity name.
	 */
	public RelationshipResolution resolveRoles(
			EntityRelationshipMapping mapping,
			Supplier<Stream<EntityRecord>> entities) {
		if (mapping == null) {
			throw new IllegalArgumentException("mapping is required");
		}
		if (entities == null) {
			throw new IllegalArgumentException("source is required");
		}
		mapping.graph();
		Session session = new Session(mapping);
		java.util.Set<String> parents = new HashSet<>(involved(mapping, true));
		java.util.Set<String> children = new HashSet<>(involved(mapping, false));
		if (!parents.isEmpty()) {
			consumeAll(entities, record -> {
				if (parents.contains(record.entityName())) {
					session.index(record);
				}
			});
		}
		int indexedParentCount = session.indexedParentCount();
		if (!children.isEmpty()) {
			consumeAll(entities, record -> {
				if (children.contains(record.entityName())) {
					session.resolve(record);
				}
			});
		}
		session.recordFootprint(indexedParentCount);
		return new RelationshipResolution(session.relationshipsOut, session.failures, indexedParentCount);
	}

	private static void consumeAll(Supplier<Stream<EntityRecord>> entities, java.util.function.Consumer<EntityRecord> consumer) {
		try (Stream<EntityRecord> records = entities.get()) {
			if (records == null) {
				throw new IllegalArgumentException("entity record stream is required");
			}
			records.forEach(record -> {
				if (record != null && record.entityName() != null) {
					consumer.accept(record);
				}
			});
		}
	}

	private static void consume(EntityRecordSource source, String entityName, java.util.function.Consumer<EntityRecord> consumer) {
		try (Stream<EntityRecord> records = source.open(entityName)) {
			if (records == null) {
				throw new IllegalArgumentException("entity record stream is required");
			}
			records.forEach(record -> {
				if (record != null && entityName.equals(record.entityName())) {
					consumer.accept(record);
				}
			});
		}
	}

	private static List<String> involved(EntityRelationshipMapping mapping, boolean parents) {
		List<String> names = new ArrayList<>();
		for (EntityDefinition entity : mapping.entities()) {
			boolean involved = mapping.relationships().stream()
					.anyMatch(relationship -> parents
							? relationship.parent().equals(entity.name())
							: relationship.child().equals(entity.name()));
			if (involved) {
				names.add(entity.name());
			}
		}
		return names;
	}

	private static final class Session {
		private final List<RelationshipDefinition> relationships;
		private final Map<RelationshipDefinition, EdgeState> edges = new HashMap<>();
		private final Map<String, Map<String, IdentityState>> identities = new HashMap<>();
		private final List<ResolvedRelationship> relationshipsOut = new ArrayList<>();
		private final List<RelationshipResolutionFailure> failures = new ArrayList<>();

		private Session(EntityRelationshipMapping mapping) {
			this.relationships = mapping.relationships();
			for (RelationshipDefinition relationship : relationships) {
				edges.put(relationship, new EdgeState(relationship));
			}
		}

		private void index(EntityRecord record) {
			String identity = identity(record);
			if (identity == null) {
				return;
			}
			IdentityState state = identities
					.computeIfAbsent(record.entityName(), ignored -> new HashMap<>())
					.get(identity);
			if (state == null) {
				identities.get(record.entityName()).put(identity, new IdentityState(record.sourceRecordNumber()));
				for (RelationshipDefinition relationship : relationships) {
					if (relationship.parent().equals(record.entityName())) {
						indexEdge(edges.get(relationship), record, identity);
					}
				}
				return;
			}
			if (!relationshipKeyConflicts(record, identity)) {
				return;
			}
			if (!state.duplicate) {
				state.duplicate = true;
				unindex(record.entityName(), identity);
			}
			failures.add(new RelationshipResolutionFailure(
					RelationshipResolutionFailure.Kind.DUPLICATE_PARENT_IDENTITY,
					record.entityName(),
					null,
					null,
					record.sourceRecordNumber(),
					"Duplicate source identity '" + identity + "' for entity '" + record.entityName()
							+ "' at source records " + state.firstRecordNumber + " and "
							+ record.sourceRecordNumber() + "."));
		}

		private boolean relationshipKeyConflicts(EntityRecord record, String identity) {
			for (RelationshipDefinition relationship : relationships) {
				if (!relationship.parent().equals(record.entityName())) {
					continue;
				}
				List<String> key = correlation(record, relationship, true);
				List<String> indexed = indexedKey(edges.get(relationship), identity);
				if (key == null && indexed == null) {
					continue;
				}
				if (key == null || indexed == null || !key.equals(indexed)) {
					return true;
				}
			}
			return false;
		}

		private static List<String> indexedKey(EdgeState edge, String identity) {
			for (Map.Entry<List<String>, List<ParentRef>> entry : edge.parents.entrySet()) {
				for (ParentRef ref : entry.getValue()) {
					if (identity.equals(ref.sourceIdentity())) {
						return entry.getKey();
					}
				}
			}
			return null;
		}

		private void indexEdge(EdgeState edge, EntityRecord record, String identity) {
			List<String> key = correlation(record, edge.relationship, true);
			if (key == null) {
				return;
			}
			edge.parents.computeIfAbsent(key, ignored -> new ArrayList<>())
					.add(new ParentRef(record.entityName(), identity, record.sourceRecordNumber()));
		}

		private void unindex(String entityName, String identity) {
			for (EdgeState edge : edges.values()) {
				if (!edge.relationship.parent().equals(entityName)) {
					continue;
				}
				Iterator<Map.Entry<List<String>, List<ParentRef>>> iterator = edge.parents.entrySet().iterator();
				while (iterator.hasNext()) {
					Map.Entry<List<String>, List<ParentRef>> entry = iterator.next();
					entry.getValue().removeIf(ref -> identity.equals(ref.sourceIdentity()));
					if (entry.getValue().isEmpty()) {
						edge.duplicateParentKeys.add(entry.getKey());
						iterator.remove();
					}
				}
			}
		}

		private void resolve(EntityRecord record) {
			for (RelationshipDefinition relationship : relationships) {
				if (relationship.child().equals(record.entityName())) {
					resolveEdge(edges.get(relationship), record);
				}
			}
		}

		private void resolveEdge(EdgeState edge, EntityRecord child) {
			RelationshipDefinition relationship = edge.relationship;
			String childIdentity = identity(child);
			List<String> key = correlation(child, relationship, false);
			if (key == null) {
				failures.add(unresolved(relationship, child, childIdentity, missingKeyMessage(relationship, child, childIdentity)));
				return;
			}
			if (edge.duplicateParentKeys.contains(key)) {
				failures.add(new RelationshipResolutionFailure(
						RelationshipResolutionFailure.Kind.DUPLICATE_PARENT_IDENTITY,
						relationship.parent(),
						relationship.child(),
						childIdentity,
						child.sourceRecordNumber(),
						"Unable to resolve relationship from '" + relationship.child() + "' '" + childIdentity
								+ "' (source record " + child.sourceRecordNumber() + ") to parent '"
								+ relationship.parent() + "': parent source identity for relationship key "
								+ describe(relationship, key) + " is duplicated."));
				return;
			}
			List<ParentRef> parents = edge.parents.get(key);
			if (parents == null || parents.isEmpty()) {
				failures.add(unresolved(
						relationship,
						child,
						childIdentity,
						"Unable to resolve relationship from '" + relationship.child() + "' '" + childIdentity
								+ "' (source record " + child.sourceRecordNumber() + ") to parent '"
								+ relationship.parent() + "': relationship key " + describe(relationship, key)
								+ " does not match a parent."));
				return;
			}
			if (relationship.cardinality() != RelationshipCardinality.MANY_TO_ONE && parents.size() > 1) {
				failures.add(cardinality(
						relationship,
						child,
						childIdentity,
						"Unable to resolve relationship from '" + relationship.child() + "' '" + childIdentity
								+ "' (source record " + child.sourceRecordNumber() + ") to parent '"
								+ relationship.parent() + "': relationship key " + describe(relationship, key)
								+ " matches multiple parent records."));
				return;
			}
			if (relationship.cardinality() == RelationshipCardinality.ONE_TO_ONE) {
				resolveOneToOne(edge, child, childIdentity, key, parents.get(0));
				return;
			}
			if (relationship.cardinality() == RelationshipCardinality.MANY_TO_ONE) {
				resolveManyToOne(edge, child, childIdentity, key, parents);
				return;
			}
			for (ParentRef parent : parents) {
				relationshipsOut.add(resolved(relationship, parent, child, childIdentity, key));
			}
		}

		private void resolveOneToOne(
				EdgeState edge,
				EntityRecord child,
				String childIdentity,
				List<String> key,
				ParentRef parent) {
			OneToOneMatch existing = edge.oneToOne.get(key);
			ResolvedRelationship relationship = resolved(edge.relationship, parent, child, childIdentity, key);
			if (existing == null) {
				edge.oneToOne.put(key, new OneToOneMatch(relationship));
				relationshipsOut.add(relationship);
				return;
			}
			if (childIdentity != null && childIdentity.equals(existing.relationship.childIdentity())) {
				return;
			}
			if (!existing.violated) {
				existing.violated = true;
				relationshipsOut.remove(existing.relationship);
				failures.add(cardinality(edge.relationship, null, existing.relationship.childIdentity(), oneToOneMessage(
						edge.relationship, parent, existing.relationship.childIdentity(), childIdentity)));
			}
			failures.add(cardinality(edge.relationship, child, childIdentity, oneToOneMessage(
					edge.relationship, parent, existing.relationship.childIdentity(), childIdentity)));
		}

		private void resolveManyToOne(
				EdgeState edge,
				EntityRecord child,
				String childIdentity,
				List<String> key,
				List<ParentRef> parents) {
			ManyToOneGroup group = edge.manyToOne.computeIfAbsent(key, ignored -> new ManyToOneGroup());
			if (group.violated) {
				failures.add(cardinality(
						edge.relationship,
						child,
						childIdentity,
						manyToOneMessage(edge.relationship, key)));
				return;
			}
			if (!group.childIdentities.isEmpty() && !group.childIdentities.contains(childIdentity)) {
				group.violated = true;
				relationshipsOut.removeAll(group.relationships);
				for (String previous : group.childIdentities) {
					failures.add(cardinality(
							edge.relationship,
							null,
							previous,
							manyToOneMessage(edge.relationship, key)));
				}
				failures.add(cardinality(edge.relationship, child, childIdentity, manyToOneMessage(edge.relationship, key)));
				group.childIdentities.add(childIdentity);
				return;
			}
			group.childIdentities.add(childIdentity);
			for (ParentRef parent : parents) {
				ResolvedRelationship relationship = resolved(edge.relationship, parent, child, childIdentity, key);
				group.relationships.add(relationship);
				relationshipsOut.add(relationship);
			}
		}

		private int indexedParentCount() {
			int count = 0;
			for (EdgeState edge : edges.values()) {
				for (List<ParentRef> refs : edge.parents.values()) {
					count += refs.size();
				}
			}
			return count;
		}

		private void recordFootprint(int indexedParents) {
			long bytes = indexedParents * 128L;
			for (EdgeState edge : edges.values()) {
				bytes += 64L;
				for (List<String> key : edge.parents.keySet()) {
					bytes += 32L;
					for (String part : key) {
						bytes += part == null ? 0L : part.length() * 2L;
					}
				}
			}
			com.etp.migration.processing.PipelineTiming.set("relationshipIndexedParents", indexedParents);
			com.etp.migration.processing.PipelineTiming.set("relationshipResultCount", relationshipsOut.size());
			com.etp.migration.processing.PipelineTiming.set("relationshipIndexBytes", bytes);
		}

		private static ResolvedRelationship resolved(
				RelationshipDefinition relationship,
				ParentRef parent,
				EntityRecord child,
				String childIdentity,
				List<String> key) {
			List<RelationshipKeyMatch> matches = new ArrayList<>();
			for (int i = 0; i < relationship.keys().size(); i++) {
				RelationshipKey relationshipKey = relationship.keys().get(i);
				matches.add(new RelationshipKeyMatch(
						relationshipKey.parentField(),
						relationshipKey.childField(),
						key.get(i)));
			}
			return new ResolvedRelationship(
					parent.entityName(),
					parent.sourceIdentity(),
					parent.sourceRecordNumber(),
					relationship.child(),
					childIdentity,
					child.sourceRecordNumber(),
					relationship.cardinality(),
					matches);
		}

		private static RelationshipResolutionFailure unresolved(
				RelationshipDefinition relationship,
				EntityRecord child,
				String childIdentity,
				String message) {
			return new RelationshipResolutionFailure(
					RelationshipResolutionFailure.Kind.UNRESOLVED_PARENT,
					relationship.parent(),
					relationship.child(),
					childIdentity,
					child.sourceRecordNumber(),
					message);
		}

		private static RelationshipResolutionFailure cardinality(
				RelationshipDefinition relationship,
				EntityRecord child,
				String childIdentity,
				String message) {
			return new RelationshipResolutionFailure(
					RelationshipResolutionFailure.Kind.CARDINALITY_VIOLATION,
					relationship.parent(),
					relationship.child(),
					childIdentity,
					child == null ? null : child.sourceRecordNumber(),
					message);
		}

		private static String oneToOneMessage(
				RelationshipDefinition relationship,
				ParentRef parent,
				String firstChild,
				String secondChild) {
			return "Relationship from '" + relationship.parent() + "' to '" + relationship.child()
					+ "' is ONE_TO_ONE but parent '" + parent.sourceIdentity() + "' is referenced by '"
					+ relationship.child() + "' records '" + firstChild + "' and '" + secondChild + "'.";
		}

		private static String manyToOneMessage(RelationshipDefinition relationship, List<String> key) {
			return "Relationship from '" + relationship.parent() + "' to '" + relationship.child()
					+ "' is MANY_TO_ONE but relationship key " + describe(relationship, key)
					+ " is referenced by multiple '" + relationship.child() + "' records.";
		}

		private static String missingKeyMessage(
				RelationshipDefinition relationship,
				EntityRecord child,
				String childIdentity) {
			String missing = relationship.keys().stream()
					.map(RelationshipKey::childField)
					.filter(field -> !present(child, field))
					.findFirst()
					.orElse(relationship.keys().get(0).childField());
			return "Unable to resolve relationship from '" + relationship.child() + "' '" + childIdentity
					+ "' (source record " + child.sourceRecordNumber() + ") to parent '" + relationship.parent()
					+ "': relationship key '" + missing + "' is missing.";
		}

		private static String describe(RelationshipDefinition relationship, List<String> values) {
			if (relationship.keys().isEmpty()) {
				return "source record '" + values.get(0) + "'";
			}
			return describe(relationship.keys(), values);
		}

		private static String describe(List<RelationshipKey> keys, List<String> values) {
			StringBuilder builder = new StringBuilder();
			for (int i = 0; i < keys.size(); i++) {
				if (i > 0) {
					builder.append(", ");
				}
				builder.append(keys.get(i).childField()).append("='").append(values.get(i)).append("'");
			}
			return builder.toString();
		}

		private static List<String> correlation(
				EntityRecord record,
				RelationshipDefinition relationship,
				boolean parentSide) {
			if (relationship.keys().isEmpty()) {
				return List.of(Long.toString(record.sourceRecordNumber()));
			}
			return values(record, relationship.keys(), parentSide);
		}

		private static List<String> values(EntityRecord record, List<RelationshipKey> keys, boolean parentSide) {
			List<String> values = new ArrayList<>();
			for (RelationshipKey key : keys) {
				String field = parentSide ? key.parentField() : key.childField();
				if (!present(record, field)) {
					return null;
				}
				values.add(text(record.fields().get(field)).trim());
			}
			return List.copyOf(values);
		}

		private static boolean present(EntityRecord record, String field) {
			if (field == null || record.fields() == null || !record.fields().containsKey(field)) {
				return false;
			}
			String value = text(record.fields().get(field));
			return value != null && !value.isBlank();
		}

		private static String identity(EntityRecord record) {
			if (record.sourceIdentity() == null || record.sourceIdentity().isBlank()) {
				return null;
			}
			return record.sourceIdentity().trim();
		}

		private static String text(Object value) {
			return value == null ? null : String.valueOf(value);
		}
	}

	private record ParentRef(String entityName, String sourceIdentity, long sourceRecordNumber) {
	}

	private static final class IdentityState {
		private final long firstRecordNumber;
		private boolean duplicate;

		private IdentityState(long firstRecordNumber) {
			this.firstRecordNumber = firstRecordNumber;
		}
	}

	private static final class EdgeState {
		private final RelationshipDefinition relationship;
		private final Map<List<String>, List<ParentRef>> parents = new HashMap<>();
		private final Set<List<String>> duplicateParentKeys = new HashSet<>();
		private final Map<List<String>, OneToOneMatch> oneToOne = new HashMap<>();
		private final Map<List<String>, ManyToOneGroup> manyToOne = new HashMap<>();

		private EdgeState(RelationshipDefinition relationship) {
			this.relationship = relationship;
		}
	}

	private static final class OneToOneMatch {
		private final ResolvedRelationship relationship;
		private boolean violated;

		private OneToOneMatch(ResolvedRelationship relationship) {
			this.relationship = relationship;
		}
	}

	private static final class ManyToOneGroup {
		private final Set<String> childIdentities = new HashSet<>();
		private final List<ResolvedRelationship> relationships = new ArrayList<>();
		private boolean violated;
	}
}
