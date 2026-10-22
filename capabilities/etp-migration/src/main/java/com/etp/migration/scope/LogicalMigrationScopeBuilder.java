package com.etp.migration.scope;

import com.etp.migration.extraction.EntityRecord;
import com.etp.migration.mapping.EntityRelationshipMapping;
import com.etp.migration.mapping.RelationshipCardinality;
import com.etp.migration.relationship.ResolvedRelationship;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.springframework.stereotype.Component;

/**
 * Builds one logical scope per distinct root source identity.
 * Input streams are consumed once. Only entity name, source identity, source record number, and relationship
 * endpoints are retained. Source field maps are not read and are not stored.
 * The index lives until the returned stream is closed. It grows with distinct identities and resolved
 * relationships, not with source payloads. Scopes are assembled one root at a time.
 * Membership follows resolved relationships from the root in either direction, so a parent of an
 * in-scope entity and that parent's other children stay in the same scope. Disconnected entities
 * are omitted. This component is not used by flat migration execution.
 */
@Component
public class LogicalMigrationScopeBuilder {

	public Stream<LogicalMigrationScope> build(
			EntityRelationshipMapping mapping,
			Stream<EntityRecord> entities,
			Stream<ResolvedRelationship> relationships) {
		if (mapping == null) {
			throw new IllegalArgumentException("mapping is required");
		}
		if (mapping.rootEntity() == null || mapping.rootEntity().isBlank()) {
			throw new IllegalArgumentException("rootEntity is required");
		}
		Index index = new Index(mapping.rootEntity().trim());
		try (Stream<EntityRecord> entityStream = entities == null ? Stream.empty() : entities;
				Stream<ResolvedRelationship> relationshipStream = relationships == null ? Stream.empty() : relationships) {
			entityStream.forEach(index::addEntity);
			relationshipStream.forEach(index::addRelationship);
		}
		index.recordFootprint();
		return index.scopes();
	}

	private static final class Index {
		private final String rootEntity;
		private final Map<Ref, Long> provenance = new LinkedHashMap<>();
		private final Map<Ref, List<Link>> children = new HashMap<>();
		private final Map<Ref, List<Link>> parents = new HashMap<>();
		private final Set<String> rootIdentities = new LinkedHashSet<>();

		private Index(String rootEntity) {
			this.rootEntity = rootEntity;
		}

		private void addEntity(EntityRecord record) {
			if (record == null || record.entityName() == null || record.entityName().isBlank()) {
				return;
			}
			String identity = identity(record.sourceIdentity());
			if (identity == null) {
				return;
			}
			String entityName = record.entityName().trim();
			provenance.putIfAbsent(new Ref(entityName, identity), record.sourceRecordNumber());
			if (rootEntity.equals(entityName)) {
				rootIdentities.add(identity);
			}
		}

		private void addRelationship(ResolvedRelationship relationship) {
			if (relationship == null) {
				return;
			}
			String parentEntity = trim(relationship.parentEntity());
			String childEntity = trim(relationship.childEntity());
			String parentIdentity = identity(relationship.parentIdentity());
			String childIdentity = identity(relationship.childIdentity());
			if (parentEntity == null || childEntity == null || parentIdentity == null || childIdentity == null) {
				return;
			}
			Ref parent = new Ref(parentEntity, parentIdentity);
			Ref child = new Ref(childEntity, childIdentity);
			provenance.putIfAbsent(parent, relationship.parentSourceRecordNumber());
			provenance.putIfAbsent(child, relationship.childSourceRecordNumber());
			children.computeIfAbsent(parent, ignored -> new ArrayList<>())
					.add(new Link(child, relationship.cardinality()));
			parents.computeIfAbsent(child, ignored -> new ArrayList<>())
					.add(new Link(parent, relationship.cardinality()));
			if (rootEntity.equals(parentEntity)) {
				rootIdentities.add(parentIdentity);
			}
		}

		private Stream<LogicalMigrationScope> scopes() {
			Iterator<String> identities = new ArrayList<>(rootIdentities).iterator();
			return StreamSupport.stream(
							Spliterators.spliteratorUnknownSize(new Iterator<LogicalMigrationScope>() {
								@Override
								public boolean hasNext() {
									return identities.hasNext();
								}

								@Override
								public LogicalMigrationScope next() {
									return scope(identities.next());
								}
							}, Spliterator.ORDERED),
							false)
					.onClose(this::clear);
		}

		private LogicalMigrationScope scope(String rootIdentity) {
			Ref root = new Ref(rootEntity, rootIdentity);
			Map<Ref, ScopedEntity> members = new LinkedHashMap<>();
			members.put(root, entity(root));
			List<ScopedRelationship> relationships = new ArrayList<>();
			Set<String> seenRelationships = new HashSet<>();
			ArrayDeque<Ref> pending = new ArrayDeque<>();
			pending.add(root);
			while (!pending.isEmpty()) {
				Ref current = pending.removeFirst();
				for (Link link : children.getOrDefault(current, List.of())) {
					connect(current, link.child(), link.cardinality(), members, relationships, seenRelationships, pending);
				}
				for (Link link : parents.getOrDefault(current, List.of())) {
					connect(link.child(), current, link.cardinality(), members, relationships, seenRelationships, pending);
				}
			}
			com.etp.migration.processing.PipelineTiming.max("maxScopeMembers", members.size());
			return new LogicalMigrationScope(
					rootEntity,
					rootIdentity,
					List.copyOf(members.values()),
					List.copyOf(relationships));
		}

		private void connect(
				Ref parent,
				Ref child,
				RelationshipCardinality cardinality,
				Map<Ref, ScopedEntity> members,
				List<ScopedRelationship> relationships,
				Set<String> seenRelationships,
				ArrayDeque<Ref> pending) {
			String relationshipKey = parent.entityName() + "\0" + parent.sourceIdentity()
					+ "\0" + child.entityName() + "\0" + child.sourceIdentity();
			if (seenRelationships.add(relationshipKey)) {
				relationships.add(new ScopedRelationship(
						parent.entityName(),
						parent.sourceIdentity(),
						child.entityName(),
						child.sourceIdentity(),
						cardinality));
			}
			addMember(parent, members, pending);
			addMember(child, members, pending);
		}

		private void addMember(Ref ref, Map<Ref, ScopedEntity> members, ArrayDeque<Ref> pending) {
			if (members.containsKey(ref)) {
				return;
			}
			members.put(ref, entity(ref));
			pending.add(ref);
		}

		private ScopedEntity entity(Ref ref) {
			return new ScopedEntity(ref.entityName(), ref.sourceIdentity(), provenance.getOrDefault(ref, 0L));
		}

		private void clear() {
			provenance.clear();
			children.clear();
			parents.clear();
			rootIdentities.clear();
		}

		private void recordFootprint() {
			long relationships = 0L;
			long bytes = 0L;
			for (Map.Entry<Ref, Long> entry : provenance.entrySet()) {
				Ref ref = entry.getKey();
				bytes += 96L + ref.entityName().length() * 2L + ref.sourceIdentity().length() * 2L;
			}
			for (List<Link> links : children.values()) {
				relationships += links.size();
				bytes += 48L + links.size() * 72L;
			}
			com.etp.migration.processing.PipelineTiming.set("scopeIdentityCount", provenance.size());
			com.etp.migration.processing.PipelineTiming.set("scopeRelationshipCount", relationships);
			com.etp.migration.processing.PipelineTiming.set("scopeIndexBytes", bytes);
		}

		private static String identity(String value) {
			if (value == null || value.isBlank()) {
				return null;
			}
			return value.trim();
		}

		private static String trim(String value) {
			if (value == null || value.isBlank()) {
				return null;
			}
			return value.trim();
		}
	}

	private record Ref(String entityName, String sourceIdentity) {
	}

	private record Link(Ref child, RelationshipCardinality cardinality) {
	}
}
