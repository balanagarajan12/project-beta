package com.etp.migration.relationship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.etp.migration.extraction.EntityRecord;
import com.etp.migration.mapping.EntityDefinition;
import com.etp.migration.mapping.EntityRelationshipMapping;
import com.etp.migration.mapping.RelationshipCardinality;
import com.etp.migration.mapping.RelationshipDefinition;
import com.etp.migration.mapping.RelationshipKey;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RelationshipResolverTest {

	private final RelationshipResolver resolver = new RelationshipResolver();

	@Test
	void resolvesOneParentToManyChildren() {
		RelationshipResolution resolution = resolver.resolve(
				chain("Customer", "customer_id", "Account", "account_id", "customer_id"),
				records(Map.of(
						"Customer", List.of(
								row("Customer", 1, "C001", "customer_id", "C001", "name", "John"),
								row("Customer", 2, "C002", "customer_id", "C002", "name", "Mary")),
						"Account", List.of(
								row("Account", 1, "A001", "account_id", "A001", "customer_id", "C001"),
								row("Account", 2, "A002", "account_id", "A002", "customer_id", "C001"),
								row("Account", 3, "A003", "account_id", "A003", "customer_id", "C002")))));

		assertThat(resolution.failures()).isEmpty();
		assertThat(resolution.relationships())
				.extracting(ResolvedRelationship::parentIdentity, ResolvedRelationship::childIdentity)
				.containsExactly(
						tuple("C001", "A001"),
						tuple("C001", "A002"),
						tuple("C002", "A003"));
		assertThat(resolution.indexedParentCount()).isEqualTo(2);
	}

	@Test
	void resolvesThreeLevelsFromConfiguredKeys() {
		EntityRelationshipMapping mapping = mapping(
				"Customer",
				List.of(entity("Customer", "customer_id"), entity("Account", "account_id"), entity("Subscription", "subscription_id")),
				List.of(
						edge("Customer", "Account", RelationshipCardinality.ONE_TO_MANY, "customer_id", "customer_id"),
						edge("Account", "Subscription", RelationshipCardinality.ONE_TO_MANY, "account_id", "account_id")));

		RelationshipResolution resolution = resolver.resolve(mapping, records(Map.of(
				"Customer", List.of(row("Customer", 1, "C001", "customer_id", "C001")),
				"Account", List.of(
						row("Account", 1, "A001", "account_id", "A001", "customer_id", "C001"),
						row("Account", 2, "A002", "account_id", "A002", "customer_id", "C001")),
				"Subscription", List.of(
						row("Subscription", 1, "S001", "subscription_id", "S001", "account_id", "A001"),
						row("Subscription", 2, "S002", "subscription_id", "S002", "account_id", "A002")))));

		assertThat(resolution.failures()).isEmpty();
		assertThat(resolution.relationships())
				.extracting(ResolvedRelationship::parentEntity, ResolvedRelationship::parentIdentity, ResolvedRelationship::childEntity, ResolvedRelationship::childIdentity)
				.containsExactly(
						tuple("Customer", "C001", "Account", "A001"),
						tuple("Customer", "C001", "Account", "A002"),
						tuple("Account", "A001", "Subscription", "S001"),
						tuple("Account", "A002", "Subscription", "S002"));
	}

	@Test
	void resolvesProductVariantPriceWithTheSameResolver() {
		EntityRelationshipMapping mapping = mapping(
				"Product",
				List.of(entity("Product", "product_id"), entity("Variant", "variant_id"), entity("Price", "price_id")),
				List.of(
						edge("Product", "Variant", RelationshipCardinality.ONE_TO_MANY, "product_id", "product_id"),
						edge("Variant", "Price", RelationshipCardinality.ONE_TO_MANY, "variant_id", "variant_id")));

		RelationshipResolution resolution = resolver.resolve(mapping, records(Map.of(
				"Product", List.of(row("Product", 1, "P1", "product_id", "P1")),
				"Variant", List.of(row("Variant", 1, "V1", "variant_id", "V1", "product_id", "P1")),
				"Price", List.of(row("Price", 1, "PR1", "price_id", "PR1", "variant_id", "V1")))));

		assertThat(resolution.relationships())
				.extracting(ResolvedRelationship::parentIdentity, ResolvedRelationship::childIdentity)
				.containsExactly(tuple("P1", "V1"), tuple("V1", "PR1"));
	}

	@Test
	void resolvesArbitraryEntityNames() {
		EntityRelationshipMapping mapping = mapping(
				"Alpha",
				List.of(entity("Alpha", "alpha_code"), entity("Beta", "beta_code"), entity("Gamma", "gamma_code")),
				List.of(
						edge("Alpha", "Beta", RelationshipCardinality.ONE_TO_MANY, "alpha_code", "alpha_ref"),
						edge("Beta", "Gamma", RelationshipCardinality.ONE_TO_MANY, "beta_code", "beta_ref")));

		RelationshipResolution resolution = resolver.resolve(mapping, records(Map.of(
				"Alpha", List.of(row("Alpha", 1, "AX", "alpha_code", "AX")),
				"Beta", List.of(row("Beta", 1, "BX", "beta_code", "BX", "alpha_ref", "AX")),
				"Gamma", List.of(row("Gamma", 1, "GX", "gamma_code", "GX", "beta_ref", "BX")))));

		assertThat(resolution.failures()).isEmpty();
		assertThat(resolution.relationships())
				.extracting(ResolvedRelationship::parentEntity, ResolvedRelationship::childEntity, ResolvedRelationship::parentIdentity, ResolvedRelationship::childIdentity)
				.containsExactly(
						tuple("Alpha", "Beta", "AX", "BX"),
						tuple("Beta", "Gamma", "BX", "GX"));
		assertThat(resolution.relationships().get(0).relationshipKeys())
				.containsExactly(new RelationshipKeyMatch("alpha_code", "alpha_ref", "AX"));
	}

	@Test
	void reportsMissingParentWithoutDroppingTheChild() {
		RelationshipResolution resolution = resolver.resolve(
				chain("Customer", "customer_id", "Account", "account_id", "customer_id"),
				records(Map.of(
						"Customer", List.of(row("Customer", 1, "C001", "customer_id", "C001")),
						"Account", List.of(
								row("Account", 1, "A001", "account_id", "A001", "customer_id", "C001"),
								row("Account", 4, "A100", "account_id", "A100", "customer_id", "C999")))));

		assertThat(resolution.relationships())
				.extracting(ResolvedRelationship::childIdentity)
				.containsExactly("A001");
		assertThat(resolution.failures()).singleElement().satisfies(failure -> {
			assertThat(failure.kind()).isEqualTo(RelationshipResolutionFailure.Kind.UNRESOLVED_PARENT);
			assertThat(failure.childEntity()).isEqualTo("Account");
			assertThat(failure.childIdentity()).isEqualTo("A100");
			assertThat(failure.childSourceRecordNumber()).isEqualTo(4L);
			assertThat(failure.parentEntity()).isEqualTo("Customer");
			assertThat(failure.message()).contains("customer_id='C999'").contains("source record 4");
		});
	}

	@Test
	void detectsOneToOneViolations() {
		EntityRelationshipMapping mapping = mapping(
				"Customer",
				List.of(entity("Customer", "customer_id"), entity("Profile", "profile_id")),
				List.of(edge("Customer", "Profile", RelationshipCardinality.ONE_TO_ONE, "customer_id", "customer_id")));

		RelationshipResolution resolution = resolver.resolve(mapping, records(Map.of(
				"Customer", List.of(row("Customer", 1, "C001", "customer_id", "C001")),
				"Profile", List.of(
						row("Profile", 1, "P1", "profile_id", "P1", "customer_id", "C001"),
						row("Profile", 2, "P2", "profile_id", "P2", "customer_id", "C001")))));

		assertThat(resolution.relationships()).isEmpty();
		assertThat(resolution.failures()).hasSize(2)
				.allSatisfy(failure -> assertThat(failure.kind())
						.isEqualTo(RelationshipResolutionFailure.Kind.CARDINALITY_VIOLATION));
		assertThat(resolution.failures())
				.extracting(RelationshipResolutionFailure::childIdentity)
				.containsExactly("P1", "P2");
		assertThat(resolution.failures().get(0).message()).contains("ONE_TO_ONE").contains("C001");
	}

	@Test
	void resolvesManyToOneFromParentToChildDirection() {
		EntityRelationshipMapping mapping = mapping(
				"Beta",
				List.of(entity("Beta", "beta_id"), entity("Alpha", "alpha_id")),
				List.of(edge("Beta", "Alpha", RelationshipCardinality.MANY_TO_ONE, "alpha_id", "alpha_id")));

		RelationshipResolution resolution = resolver.resolve(mapping, records(Map.of(
				"Beta", List.of(
						row("Beta", 1, "B1", "beta_id", "B1", "alpha_id", "A1"),
						row("Beta", 2, "B2", "beta_id", "B2", "alpha_id", "A1")),
				"Alpha", List.of(row("Alpha", 1, "A1", "alpha_id", "A1")))));

		assertThat(resolution.failures()).isEmpty();
		assertThat(resolution.relationships())
				.extracting(ResolvedRelationship::parentEntity, ResolvedRelationship::parentIdentity, ResolvedRelationship::childEntity, ResolvedRelationship::childIdentity)
				.containsExactly(
						tuple("Beta", "B1", "Alpha", "A1"),
						tuple("Beta", "B2", "Alpha", "A1"));
	}

	@Test
	void repeatedParentSourceIdentityResolvesOnce() {
		RelationshipResolution resolution = resolver.resolve(
				chain("Customer", "customer_id", "Account", "account_id", "customer_id"),
				records(Map.of(
						"Customer", List.of(
								row("Customer", 1, "C001", "customer_id", "C001"),
								row("Customer", 2, "C001", "customer_id", "C001")),
						"Account", List.of(row("Account", 1, "A001", "account_id", "A001", "customer_id", "C001")))));

		assertThat(resolution.failures()).isEmpty();
		assertThat(resolution.indexedParentCount()).isEqualTo(1);
		assertThat(resolution.relationships())
				.extracting(ResolvedRelationship::parentIdentity, ResolvedRelationship::childIdentity)
				.containsExactly(tuple("C001", "A001"));
	}

	@Test
	void conflictingRepeatedParentKeyFails() {
		EntityRelationshipMapping mapping = mapping(
				"Customer",
				List.of(entity("Customer", "customer_id"), entity("Account", "account_id")),
				List.of(edge("Customer", "Account", RelationshipCardinality.ONE_TO_MANY, "region_id", "region_id")));

		RelationshipResolution resolution = resolver.resolve(mapping, records(Map.of(
				"Customer", List.of(
						row("Customer", 1, "C001", "customer_id", "C001", "region_id", "R1"),
						row("Customer", 2, "C001", "customer_id", "C001", "region_id", "R2")),
				"Account", List.of(row("Account", 1, "A001", "account_id", "A001", "region_id", "R1")))));

		assertThat(resolution.relationships()).isEmpty();
		assertThat(resolution.indexedParentCount()).isZero();
		assertThat(resolution.failures())
				.extracting(RelationshipResolutionFailure::kind)
				.contains(
						RelationshipResolutionFailure.Kind.DUPLICATE_PARENT_IDENTITY,
						RelationshipResolutionFailure.Kind.DUPLICATE_PARENT_IDENTITY);
	}

	@Test
	void matchesRelationshipKeyRatherThanChildIdentity() {
		EntityRelationshipMapping mapping = mapping(
				"Customer",
				List.of(entity("Customer", "customer_id"), entity("Account", "account_id")),
				List.of(edge("Customer", "Account", RelationshipCardinality.ONE_TO_MANY, "customer_id", "owner_id")));

		RelationshipResolution resolution = resolver.resolve(mapping, records(Map.of(
				"Customer", List.of(row("Customer", 1, "C001", "customer_id", "C001")),
				"Account", List.of(row("Account", 1, "A001", "account_id", "A001", "owner_id", "C001")))));

		ResolvedRelationship relationship = resolution.relationships().get(0);
		assertThat(relationship.childIdentity()).isEqualTo("A001");
		assertThat(relationship.parentIdentity()).isEqualTo("C001");
		assertThat(relationship.relationshipKeys())
				.containsExactly(new RelationshipKeyMatch("customer_id", "owner_id", "C001"));
	}

	@Test
	void resolvesEveryConfiguredRelationship() {
		EntityRelationshipMapping mapping = mapping(
				"Customer",
				List.of(entity("Customer", "customer_id"), entity("Account", "account_id"), entity("Contract", "contract_id")),
				List.of(
						edge("Customer", "Account", RelationshipCardinality.ONE_TO_MANY, "customer_id", "customer_id"),
						edge("Customer", "Contract", RelationshipCardinality.ONE_TO_MANY, "customer_id", "customer_id")));

		RelationshipResolution resolution = resolver.resolve(mapping, records(Map.of(
				"Customer", List.of(row("Customer", 1, "C001", "customer_id", "C001")),
				"Account", List.of(row("Account", 1, "A001", "account_id", "A001", "customer_id", "C001")),
				"Contract", List.of(row("Contract", 1, "K001", "contract_id", "K001", "customer_id", "C001")))));

		assertThat(resolution.relationships())
				.extracting(ResolvedRelationship::childEntity, ResolvedRelationship::childIdentity)
				.containsExactly(tuple("Account", "A001"), tuple("Contract", "K001"));
	}

	@Test
	void resolvesEachEdgeOfADag() {
		EntityRelationshipMapping mapping = mapping(
				"Alpha",
				List.of(
						entity("Alpha", "alpha_id"),
						entity("Beta", "beta_id"),
						entity("Gamma", "gamma_id"),
						entity("Delta", "delta_id")),
				List.of(
						edge("Alpha", "Beta", RelationshipCardinality.ONE_TO_MANY, "alpha_id", "alpha_id"),
						edge("Alpha", "Gamma", RelationshipCardinality.ONE_TO_MANY, "alpha_id", "alpha_id"),
						edge("Beta", "Delta", RelationshipCardinality.ONE_TO_MANY, "beta_id", "beta_id"),
						edge("Gamma", "Delta", RelationshipCardinality.ONE_TO_MANY, "gamma_id", "gamma_id")));

		RelationshipResolution resolution = resolver.resolve(mapping, records(Map.of(
				"Alpha", List.of(row("Alpha", 1, "A", "alpha_id", "A")),
				"Beta", List.of(row("Beta", 1, "B", "beta_id", "B", "alpha_id", "A")),
				"Gamma", List.of(row("Gamma", 1, "G", "gamma_id", "G", "alpha_id", "A")),
				"Delta", List.of(row("Delta", 1, "D", "delta_id", "D", "beta_id", "B", "gamma_id", "G")))));

		assertThat(resolution.failures()).isEmpty();
		assertThat(resolution.relationships())
				.extracting(ResolvedRelationship::parentEntity, ResolvedRelationship::childEntity)
				.containsExactly(
						tuple("Alpha", "Beta"),
						tuple("Alpha", "Gamma"),
						tuple("Beta", "Delta"),
						tuple("Gamma", "Delta"));
	}

	@Test
	void parentIndexDoesNotGrowWithChildVolume() {
		List<EntityRecord> children = new java.util.ArrayList<>();
		for (int i = 1; i <= 200; i++) {
			children.add(row("Account", i, "A" + i, "account_id", "A" + i, "customer_id", "C001"));
		}
		RelationshipResolution resolution = resolver.resolve(
				chain("Customer", "customer_id", "Account", "account_id", "customer_id"),
				records(Map.of(
						"Customer", List.of(row("Customer", 1, "C001", "customer_id", "C001")),
						"Account", children)));

		assertThat(resolution.indexedParentCount()).isEqualTo(1);
		assertThat(resolution.relationships()).hasSize(200);
		assertThat(resolution.failures()).isEmpty();
	}

	private static EntityRelationshipMapping chain(
			String parent,
			String parentKey,
			String child,
			String childKey,
			String childReference) {
		return mapping(
				parent,
				List.of(entity(parent, parentKey), entity(child, childKey)),
				List.of(edge(parent, child, RelationshipCardinality.ONE_TO_MANY, parentKey, childReference)));
	}

	private static EntityRelationshipMapping mapping(
			String root,
			List<EntityDefinition> entities,
			List<RelationshipDefinition> relationships) {
		return new EntityRelationshipMapping(root, entities, relationships);
	}

	private static EntityDefinition entity(String name, String sourceKey) {
		return new EntityDefinition(name, name.toLowerCase(), sourceKey, "id");
	}

	private static RelationshipDefinition edge(
			String parent,
			String child,
			RelationshipCardinality cardinality,
			String parentField,
			String childField) {
		return new RelationshipDefinition(parent, child, cardinality, List.of(new RelationshipKey(parentField, childField)));
	}

	private static EntityRecordSource records(Map<String, List<EntityRecord>> byEntity) {
		return entityName -> byEntity.getOrDefault(entityName, List.of()).stream();
	}

	private static EntityRecord row(String entity, long number, String identity, Object... pairs) {
		Map<String, Object> fields = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			fields.put((String) pairs[i], pairs[i + 1]);
		}
		return new EntityRecord(entity, number, identity, fields);
	}
}
