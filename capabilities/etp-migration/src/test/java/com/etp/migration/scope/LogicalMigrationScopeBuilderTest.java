package com.etp.migration.scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.etp.migration.extraction.EntityRecord;
import com.etp.migration.mapping.EntityDefinition;
import com.etp.migration.mapping.EntityRelationshipMapping;
import com.etp.migration.mapping.RelationshipCardinality;
import com.etp.migration.mapping.RelationshipDefinition;
import com.etp.migration.mapping.RelationshipKey;
import com.etp.migration.relationship.ResolvedRelationship;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class LogicalMigrationScopeBuilderTest {

	private final LogicalMigrationScopeBuilder builder = new LogicalMigrationScopeBuilder();

	@Test
	void buildsOneRootWithOneChild() {
		LogicalMigrationScope scope = only(build(
				mapping("Location", entity("Location", "location_id"), entity("Address", "address_id")),
				List.of(entity("Location", "L001", 1), entity("Address", "A001", 2)),
				List.of(link("Location", "L001", 1, "Address", "A001", 2))));

		assertThat(scope.rootEntity()).isEqualTo("Location");
		assertThat(scope.rootIdentity()).isEqualTo("L001");
		assertThat(scope.entities())
				.extracting(ScopedEntity::entityName, ScopedEntity::sourceIdentity)
				.containsExactly(tuple("Location", "L001"), tuple("Address", "A001"));
		assertThat(scope.relationships())
				.extracting(ScopedRelationship::parentIdentity, ScopedRelationship::childIdentity)
				.containsExactly(tuple("L001", "A001"));
	}

	@Test
	void associatesEveryChildWithTheSameRoot() {
		LogicalMigrationScope scope = only(build(
				mapping("Location", entity("Location", "location_id"), entity("Address", "address_id")),
				List.of(entity("Location", "L001", 1)),
				List.of(
						link("Location", "L001", 1, "Address", "A001", 2),
						link("Location", "L001", 1, "Address", "A002", 3),
						link("Location", "L001", 1, "Address", "A003", 4))));

		assertThat(scope.entities())
				.extracting(ScopedEntity::sourceIdentity)
				.containsExactly("L001", "A001", "A002", "A003");
		assertThat(scope.relationships()).hasSize(3);
	}

	@Test
	void buildsAMultiLevelHierarchy() {
		LogicalMigrationScope scope = only(build(
				mapping(
						"Location",
						entity("Location", "location_id"),
						entity("Address", "address_id"),
						entity("Site", "site_id")),
				List.of(
						entity("Location", "L001", 1),
						entity("Address", "A001", 2),
						entity("Site", "S001", 3)),
				List.of(
						link("Address", "A001", 2, "Site", "S001", 3),
						link("Location", "L001", 1, "Address", "A001", 2))));

		assertThat(scope.entities())
				.extracting(ScopedEntity::entityName, ScopedEntity::sourceIdentity)
				.containsExactly(
						tuple("Location", "L001"),
						tuple("Address", "A001"),
						tuple("Site", "S001"));
		assertThat(scope.relationships())
				.extracting(ScopedRelationship::parentEntity, ScopedRelationship::childEntity)
				.containsExactly(tuple("Location", "Address"), tuple("Address", "Site"));
	}

	@Test
	void createsASeparateScopePerRootIdentity() {
		List<LogicalMigrationScope> scopes = build(
				mapping("Location", entity("Location", "location_id"), entity("Address", "address_id")),
				List.of(entity("Location", "L001", 1), entity("Location", "L002", 2)),
				List.of(
						link("Location", "L001", 1, "Address", "A001", 3),
						link("Location", "L002", 2, "Address", "A002", 4)));

		assertThat(scopes).extracting(LogicalMigrationScope::rootIdentity).containsExactly("L001", "L002");
		assertThat(scopes.get(0).contains("Address", "A001")).isTrue();
		assertThat(scopes.get(0).contains("Address", "A002")).isFalse();
		assertThat(scopes.get(1).contains("Location", "L001")).isFalse();
		assertThat(scopes.get(1).contains("Address", "A002")).isTrue();
	}

	@Test
	void keepsDiamondMemberOnceAndBothPaths() {
		LogicalMigrationScope scope = only(build(
				mapping(
						"Alpha",
						entity("Alpha", "alpha_id"),
						entity("Beta", "beta_id"),
						entity("Gamma", "gamma_id"),
						entity("Delta", "delta_id")),
				List.of(entity("Alpha", "A1", 1)),
				List.of(
						link("Alpha", "A1", 1, "Beta", "B1", 2),
						link("Alpha", "A1", 1, "Gamma", "C1", 3),
						link("Beta", "B1", 2, "Delta", "D1", 4),
						link("Gamma", "C1", 3, "Delta", "D1", 4))));

		assertThat(scope.entities())
				.extracting(ScopedEntity::sourceIdentity)
				.containsExactly("A1", "B1", "C1", "D1");
		assertThat(scope.entities().stream().filter(entity -> entity.sourceIdentity().equals("D1"))).hasSize(1);
		assertThat(scope.relationships())
				.extracting(ScopedRelationship::parentIdentity, ScopedRelationship::childIdentity)
				.containsExactly(
						tuple("A1", "B1"),
						tuple("A1", "C1"),
						tuple("B1", "D1"),
						tuple("C1", "D1"));
	}

	@Test
	void doesNotDuplicateMembershipWhenAnIdentityIsEncounteredAgain() {
		LogicalMigrationScope scope = only(build(
				mapping("Location", entity("Location", "location_id"), entity("Address", "address_id")),
				List.of(
						entity("Location", "L001", 1),
						entity("Address", "A001", 105),
						entity("Address", "A001", 400)),
				List.of(
						link("Location", "L001", 1, "Address", "A001", 105),
						link("Location", "L001", 1, "Address", "A001", 400))));

		assertThat(scope.entities()).hasSize(2);
		assertThat(scope.member("Address", "A001").sourceRecordNumber()).isEqualTo(105L);
		assertThat(scope.relationships()).hasSize(1);
	}

	@Test
	void preservesEveryRelationshipThatReachesTheRoot() {
		LogicalMigrationScope scope = only(build(
				mapping(
						"Location",
						entity("Location", "location_id"),
						entity("Address", "address_id"),
						entity("Site", "site_id")),
				List.of(entity("Location", "L001", 1)),
				List.of(
						link("Location", "L001", 1, "Address", "A001", 2),
						link("Location", "L001", 1, "Address", "A002", 3),
						link("Address", "A001", 2, "Site", "S001", 4),
						link("Address", "A001", 2, "Site", "S002", 5),
						link("Address", "A002", 3, "Site", "S003", 6))));

		assertThat(scope.relationships())
				.extracting(ScopedRelationship::parentIdentity, ScopedRelationship::childIdentity)
				.containsExactly(
						tuple("L001", "A001"),
						tuple("L001", "A002"),
						tuple("A001", "S001"),
						tuple("A001", "S002"),
						tuple("A002", "S003"));
		assertThat(scope.entities()).hasSize(6);
	}

	@Test
	void includesAParentOfAnInScopeEntityAndThatParentsOtherChild() {
		LogicalMigrationScope scope = only(build(
				mapping(
						"AddressLocation",
						entity("AddressLocation", "source_address_id"),
						entity("GeographicAddress", "source_address_id"),
						entity("GeographicLocation", "source_address_id"),
						entity("LocationGeometry", "source_address_id")),
				List.of(
						entity("AddressLocation", "1", 1),
						entity("GeographicAddress", "1", 1),
						entity("GeographicLocation", "1", 1),
						entity("LocationGeometry", "1", 1)),
				List.of(
						link("AddressLocation", "1", 1, "GeographicAddress", "1", 1),
						link("GeographicLocation", "1", 1, "GeographicAddress", "1", 1),
						link("GeographicLocation", "1", 1, "LocationGeometry", "1", 1))));

		assertThat(scope.contains("GeographicLocation", "1")).isTrue();
		assertThat(scope.contains("LocationGeometry", "1")).isTrue();
		assertThat(scope.relationships())
				.extracting(ScopedRelationship::parentEntity, ScopedRelationship::childEntity)
				.containsExactly(
						tuple("AddressLocation", "GeographicAddress"),
						tuple("GeographicLocation", "GeographicAddress"),
						tuple("GeographicLocation", "LocationGeometry"));
	}

	@Test
	void leavesDisconnectedEntitiesOutsideTheRootScope() {
		LogicalMigrationScope scope = only(build(
				mapping(
						"Location",
						entity("Location", "location_id"),
						entity("Address", "address_id"),
						entity("Site", "site_id")),
				List.of(
						entity("Location", "L001", 1),
						entity("Address", "A001", 2),
						entity("Site", "S999", 9)),
				List.of(link("Location", "L001", 1, "Address", "A001", 2))));

		assertThat(scope.contains("Site", "S999")).isFalse();
		assertThat(scope.entities())
				.extracting(ScopedEntity::sourceIdentity)
				.containsExactly("L001", "A001");
	}

	@Test
	void keepsMultipleRelationshipEdgesFromTheRoot() {
		LogicalMigrationScope scope = only(build(
				mapping(
						"Location",
						entity("Location", "location_id"),
						entity("Address", "address_id"),
						entity("Site", "site_id")),
				List.of(entity("Location", "L001", 1)),
				List.of(
						link("Location", "L001", 1, "Address", "A001", 2),
						link("Location", "L001", 1, "Site", "S001", 3))));

		assertThat(scope.relationships())
				.extracting(ScopedRelationship::childEntity, ScopedRelationship::childIdentity, ScopedRelationship::cardinality)
				.containsExactly(
						tuple("Address", "A001", RelationshipCardinality.ONE_TO_MANY),
						tuple("Site", "S001", RelationshipCardinality.ONE_TO_MANY));
	}

	@Test
	void buildsTheSameWayForAProductModel() {
		LogicalMigrationScope scope = only(build(
				mapping(
						"Product",
						entity("Product", "product_id"),
						entity("Variant", "variant_id"),
						entity("Price", "price_id")),
				List.of(entity("Product", "P1", 1)),
				List.of(
						link("Product", "P1", 1, "Variant", "V1", 2),
						link("Variant", "V1", 2, "Price", "PR1", 3))));

		assertThat(scope.rootEntity()).isEqualTo("Product");
		assertThat(scope.entities())
				.extracting(ScopedEntity::sourceIdentity)
				.containsExactly("P1", "V1", "PR1");
	}

	@Test
	void buildsTheSameWayForAnArbitraryModel() {
		LogicalMigrationScope scope = only(build(
				mapping(
						"Alpha",
						entity("Alpha", "alpha_code"),
						entity("Beta", "beta_code"),
						entity("Gamma", "gamma_code")),
				List.of(entity("Alpha", "AX", 10)),
				List.of(
						link("Alpha", "AX", 10, "Beta", "BX", 11),
						link("Beta", "BX", 11, "Gamma", "GX", 12))));

		assertThat(scope.rootIdentity()).isEqualTo("AX");
		assertThat(scope.member("Gamma", "GX").sourceRecordNumber()).isEqualTo(12L);
		assertThat(scope.relationships()).hasSize(2);
	}

	@Test
	void keepsSourceRecordNumberAsProvenanceRatherThanIdentity() {
		LogicalMigrationScope scope = only(build(
				mapping("Location", entity("Location", "location_id"), entity("Address", "address_id")),
				List.of(entity("Location", "L001", 7)),
				List.of(link("Location", "L001", 7, "Address", "A001", 105))));

		ScopedEntity address = scope.member("Address", "A001");
		assertThat(address.sourceIdentity()).isEqualTo("A001");
		assertThat(address.sourceRecordNumber()).isEqualTo(105L);
		assertThat(address.sourceIdentity()).isNotEqualTo(Long.toString(address.sourceRecordNumber()));
	}

	@Test
	void doesNotCopyPayloadsForALargeChildSet() {
		List<EntityRecord> entities = new ArrayList<>();
		entities.add(new EntityRecord("Location", 1, "L001", Map.of("blob", "payload-marker")));
		List<ResolvedRelationship> relationships = new ArrayList<>();
		for (int i = 1; i <= 200; i++) {
			entities.add(new EntityRecord(
					"Address",
					i + 1,
					"A" + i,
					Map.of("blob", "payload-marker", "notes", "x".repeat(32))));
			relationships.add(link("Location", "L001", 1, "Address", "A" + i, i + 1));
		}

		LogicalMigrationScope scope = only(build(
				mapping("Location", entity("Location", "location_id"), entity("Address", "address_id")),
				entities,
				relationships));

		assertThat(scope.entities()).hasSize(201);
		assertThat(scope.relationships()).hasSize(200);
		assertThat(scope.toString()).doesNotContain("payload-marker");
		assertThat(scope.entities()).allSatisfy(entity -> assertThat(entity.toString()).doesNotContain("payload-marker"));
	}

	@Test
	void rootWithoutRelationshipsIsStillAScope() {
		LogicalMigrationScope scope = only(build(
				mapping("Location", entity("Location", "location_id")),
				List.of(entity("Location", "L001", 4)),
				List.of()));

		assertThat(scope.rootIdentity()).isEqualTo("L001");
		assertThat(scope.entities()).containsExactly(new ScopedEntity("Location", "L001", 4));
		assertThat(scope.relationships()).isEmpty();
	}

	@Test
	void doesNotInventAScopeForAnUnrelatedEntity() {
		List<LogicalMigrationScope> scopes = build(
				mapping(
						"Location",
						entity("Location", "location_id"),
						entity("Address", "address_id"),
						entity("Site", "site_id")),
				List.of(entity("Address", "A001", 2), entity("Site", "S001", 3)),
				List.of(link("Address", "A001", 2, "Site", "S001", 3)));

		assertThat(scopes).isEmpty();
	}

	private List<LogicalMigrationScope> build(
			EntityRelationshipMapping mapping,
			List<EntityRecord> entities,
			List<ResolvedRelationship> relationships) {
		try (Stream<LogicalMigrationScope> scopes = builder.build(mapping, entities.stream(), relationships.stream())) {
			return scopes.toList();
		}
	}

	private static LogicalMigrationScope only(List<LogicalMigrationScope> scopes) {
		assertThat(scopes).hasSize(1);
		return scopes.get(0);
	}

	private static EntityRelationshipMapping mapping(String root, EntityDefinition... entities) {
		List<RelationshipDefinition> relationships = new ArrayList<>();
		for (int i = 1; i < entities.length; i++) {
			relationships.add(new RelationshipDefinition(
					entities[i - 1].name(),
					entities[i].name(),
					RelationshipCardinality.ONE_TO_MANY,
					List.of(new RelationshipKey(entities[i - 1].sourceKey(), entities[i - 1].sourceKey()))));
		}
		return new EntityRelationshipMapping(root, List.of(entities), relationships);
	}

	private static EntityDefinition entity(String name, String sourceKey) {
		return new EntityDefinition(name, name.toLowerCase(), sourceKey, "id");
	}

	private static EntityRecord entity(String name, String identity, long recordNumber) {
		return new EntityRecord(name, recordNumber, identity, new LinkedHashMap<>());
	}

	private static ResolvedRelationship link(
			String parent,
			String parentIdentity,
			long parentRecord,
			String child,
			String childIdentity,
			long childRecord) {
		return new ResolvedRelationship(
				parent,
				parentIdentity,
				parentRecord,
				child,
				childIdentity,
				childRecord,
				RelationshipCardinality.ONE_TO_MANY,
				List.of());
	}
}
