package com.etp.migration.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.etp.migration.mapping.EntityDefinition;
import com.etp.migration.mapping.EntityRelationshipMapping;
import com.etp.migration.mapping.RelationshipCardinality;
import com.etp.migration.mapping.RelationshipDefinition;
import com.etp.migration.mapping.RelationshipKey;
import com.etp.migration.planning.ExecutionBatchPlan;
import com.etp.migration.planning.ExecutionWorkItem;
import com.etp.migration.planning.WorkItemParent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IdentityResolutionServiceTest {

	private final IdentityResolutionService service = new IdentityResolutionService();

	@Test
	void resolvesOneSourceIdentityToADifferentTargetIdentity() {
		ScriptedLookup lookup = lookup(Map.of("Address", Map.of("A001", "T001")));

		IdentityResolutionResult result = service.resolve(
				chain("Address", "address_id"),
				batch(item("Address", "A001")),
				lookup,
				10);

		assertThat(result.unresolved()).isEmpty();
		assertThat(result.resolved()).singleElement().satisfies(resolved -> {
			assertThat(resolved.source()).isEqualTo(new EntityIdentity("Address", "A001"));
			assertThat(resolved.targetIdentity()).isEqualTo("T001");
			assertThat(resolved.targetIdentity()).isNotEqualTo(resolved.source().sourceIdentity());
		});
	}

	@Test
	void resolvesManyIdentitiesOfOneEntityInOneLookup() {
		ScriptedLookup lookup = lookup(Map.of("Address", Map.of(
				"A001", "T001",
				"A002", "T002",
				"A003", "T003")));

		IdentityResolutionResult result = service.resolve(
				chain("Address", "address_id"),
				batch(item("Address", "A001"), item("Address", "A002"), item("Address", "A003")),
				lookup,
				10);

		assertThat(lookup.calls()).hasSize(1);
		assertThat(lookup.calls().get(0).sourceIdentities()).containsExactly("A001", "A002", "A003");
		assertThat(result.resolved())
				.extracting(resolved -> resolved.source().sourceIdentity(), ResolvedIdentity::targetIdentity)
				.containsExactly(tuple("A001", "T001"), tuple("A002", "T002"), tuple("A003", "T003"));
	}

	@Test
	void resolvesEachEntityTypeSeparately() {
		ScriptedLookup lookup = lookup(Map.of(
				"Location", Map.of("L001", "TL001"),
				"Address", Map.of("A001", "TA001"),
				"Site", Map.of("S001", "TS001")));

		IdentityResolutionResult result = service.resolve(
				chain("Location", "location_id", "Address", "address_id", "Site", "site_id"),
				batch(
						item("Location", "L001"),
						item("Address", "A001", parent("Location", "L001")),
						item("Site", "S001", parent("Address", "A001"))),
				lookup,
				10);

		assertThat(result.resolved())
				.extracting(resolved -> resolved.source().entityName(), resolved -> resolved.source().sourceIdentity(), ResolvedIdentity::targetIdentity)
				.containsExactly(
						tuple("Location", "L001", "TL001"),
						tuple("Address", "A001", "TA001"),
						tuple("Site", "S001", "TS001"));
	}

	@Test
	void keepsTheSameSourceTextDistinctAcrossEntityTypes() {
		ScriptedLookup lookup = lookup(Map.of(
				"Address", Map.of("A001", "TA"),
				"Site", Map.of("A001", "TS")));

		IdentityResolutionResult result = service.resolve(
				mapping(
						"Address",
						List.of(def("Address", "address_id", "id"), def("Site", "site_id", "id")),
						List.of()),
				batch(item("Address", "A001"), item("Site", "A001")),
				lookup,
				10);

		assertThat(lookup.calls())
				.extracting(Call::entityName, Call::sourceIdentities)
				.containsExactly(tuple("Address", List.of("A001")), tuple("Site", List.of("A001")));
		assertThat(result.resolved())
				.extracting(resolved -> resolved.source().entityName(), ResolvedIdentity::targetIdentity)
				.containsExactly(tuple("Address", "TA"), tuple("Site", "TS"));
	}

	@Test
	void preservesATargetKeyThatDiffersFromTheSourceKey() {
		ScriptedLookup lookup = lookup(Map.of("Party", Map.of("C001", "T9")));
		EntityRelationshipMapping mapping = mapping(
				"Party",
				List.of(new EntityDefinition("Party", "party_record", "customer_id", "id")),
				List.of());

		ResolvedIdentity resolved = service.resolve(mapping, batch(item("Party", "C001")), lookup, 10)
				.resolved()
				.get(0);

		assertThat(resolved.sourceKey()).isEqualTo("customer_id");
		assertThat(resolved.targetKey()).isEqualTo("id");
		assertThat(resolved.targetEntity()).isEqualTo("party_record");
		assertThat(resolved.sourceKey()).isNotEqualTo(resolved.targetKey());
		assertThat(resolved.targetIdentity()).isEqualTo("T9");
	}

	@Test
	void reportsAMissingTargetIdentityWithoutInventingOne() {
		ScriptedLookup lookup = lookup(Map.of());

		IdentityResolutionResult result = service.resolve(
				chain("Address", "address_id"),
				batch(item("Address", "A999")),
				lookup,
				10);

		assertThat(result.resolved()).isEmpty();
		assertThat(result.unresolved()).singleElement().satisfies(unresolved -> {
			assertThat(unresolved.source()).isEqualTo(new EntityIdentity("Address", "A999"));
			assertThat(unresolved.kind()).isEqualTo(IdentityResolutionFailureKind.TARGET_IDENTITY_NOT_FOUND);
			assertThat(unresolved.message()).contains("was not found");
		});
	}

	@Test
	void doesNotCallTheProviderForAnEmptyBatch() {
		ScriptedLookup lookup = lookup(Map.of("Address", Map.of("A001", "T001")));

		IdentityResolutionResult result = service.resolve(chain("Address", "address_id"), batch(), lookup, 10);

		assertThat(result.resolved()).isEmpty();
		assertThat(result.unresolved()).isEmpty();
		assertThat(lookup.calls()).isEmpty();
	}

	@Test
	void sendsABoundedGroupInsteadOfOneCallPerIdentity() {
		ScriptedLookup lookup = lookup(Map.of());
		lookup.resolveAll = true;
		List<ExecutionWorkItem> items = new ArrayList<>();
		for (int i = 1; i <= 100; i++) {
			items.add(item("Address", "A" + i));
		}

		service.resolve(chain("Address", "address_id"), batch(items), lookup, 100);

		assertThat(lookup.calls()).hasSize(1);
		assertThat(lookup.calls().get(0).sourceIdentities()).hasSize(100);
	}

	@Test
	void groupsABatchByEntityBeforeLookup() {
		ScriptedLookup lookup = lookup(Map.of(
				"Location", Map.of("L001", "TL1", "L002", "TL2"),
				"Address", Map.of("A001", "TA1", "A002", "TA2")));

		service.resolve(
				chain("Location", "location_id", "Address", "address_id"),
				batch(
						item("Location", "L001"),
						item("Location", "L002"),
						item("Address", "A001", parent("Location", "L001")),
						item("Address", "A002", parent("Location", "L002"))),
				lookup,
				10);

		assertThat(lookup.calls())
				.extracting(Call::entityName, Call::sourceIdentities)
				.containsExactly(
						tuple("Location", List.of("L001", "L002")),
						tuple("Address", List.of("A001", "A002")));
	}

	@Test
	void looksUpParentEntitiesBeforeDependents() {
		ScriptedLookup lookup = lookup(Map.of(
				"Location", Map.of("L001", "TL001"),
				"Address", Map.of("A001", "TA001"),
				"Site", Map.of("S001", "TS001")));

		service.resolve(
				chain("Location", "location_id", "Address", "address_id", "Site", "site_id"),
				batch(
						item("Site", "S001", parent("Address", "A001")),
						item("Address", "A001", parent("Location", "L001")),
						item("Location", "L001")),
				lookup,
				10);

		assertThat(lookup.calls()).extracting(Call::entityName).containsExactly("Location", "Address", "Site");
	}

	@Test
	void attachesTheResolvedParentTargetIdentity() {
		ScriptedLookup lookup = lookup(Map.of(
				"Location", Map.of("L001", "TL001"),
				"Address", Map.of("A001", "TA001")));

		ResolvedIdentity address = service.resolve(
				chain("Location", "location_id", "Address", "address_id"),
				batch(item("Address", "A001", parent("Location", "L001")), item("Location", "L001")),
				lookup,
				10).resolved().get(0);

		assertThat(address.source()).isEqualTo(new EntityIdentity("Address", "A001"));
		assertThat(address.targetIdentity()).isEqualTo("TA001");
		assertThat(address.parents()).containsExactly(new ParentIdentityReference(
				new EntityIdentity("Location", "L001"),
				"TL001",
				ParentIdentityStatus.RESOLVED));
	}

	@Test
	void keepsAMissingParentTargetExplicit() {
		ScriptedLookup lookup = lookup(Map.of("Address", Map.of("A999", "TA999")));

		IdentityResolutionResult result = service.resolve(
				chain("Location", "location_id", "Address", "address_id"),
				batch(item("Address", "A999", parent("Location", "L999")), item("Location", "L999")),
				lookup,
				10);

		assertThat(result.resolved()).singleElement().satisfies(address -> {
			assertThat(address.targetIdentity()).isEqualTo("TA999");
			assertThat(address.parents()).containsExactly(new ParentIdentityReference(
					new EntityIdentity("Location", "L999"),
					null,
					ParentIdentityStatus.UNRESOLVED));
		});
		assertThat(result.unresolved()).singleElement().satisfies(location -> {
			assertThat(location.source()).isEqualTo(new EntityIdentity("Location", "L999"));
			assertThat(location.kind()).isEqualTo(IdentityResolutionFailureKind.TARGET_IDENTITY_NOT_FOUND);
		});
	}

	@Test
	void retainsEveryParentReference() {
		ScriptedLookup lookup = lookup(Map.of(
				"Beta", Map.of("B1", "TB1"),
				"Gamma", Map.of("C1", "TC1"),
				"Delta", Map.of("D1", "TD1")));

		ResolvedIdentity delta = service.resolve(
				mapping(
						"Alpha",
						List.of(def("Beta", "beta_id", "id"), def("Gamma", "gamma_id", "id"), def("Delta", "delta_id", "id")),
						List.of(edge("Beta", "Delta"), edge("Gamma", "Delta"))),
				batch(item("Delta", "D1", parent("Beta", "B1"), parent("Gamma", "C1"))),
				lookup,
				10).resolved().get(0);

		assertThat(delta.parents()).containsExactly(
				new ParentIdentityReference(new EntityIdentity("Beta", "B1"), "TB1", ParentIdentityStatus.RESOLVED),
				new ParentIdentityReference(new EntityIdentity("Gamma", "C1"), "TC1", ParentIdentityStatus.RESOLVED));
	}

	@Test
	void resolvesADiamondChildOnceWithBothParents() {
		ScriptedLookup lookup = lookup(Map.of(
				"A", Map.of("A1", "TA1"),
				"B", Map.of("B1", "TB1"),
				"C", Map.of("C1", "TC1"),
				"D", Map.of("D1", "TD1")));
		EntityRelationshipMapping mapping = mapping(
				"A",
				List.of(def("A", "a_id", "id"), def("B", "b_id", "id"), def("C", "c_id", "id"), def("D", "d_id", "id")),
				List.of(edge("A", "B"), edge("A", "C"), edge("B", "D"), edge("C", "D")));

		IdentityResolutionResult result = service.resolve(
				mapping,
				batch(
						item("A", "A1"),
						item("B", "B1", parent("A", "A1")),
						item("C", "C1", parent("A", "A1")),
						item("D", "D1", parent("B", "B1"), parent("C", "C1")),
						item("D", "D1", parent("B", "B1"), parent("C", "C1"))),
				lookup,
				10);

		assertThat(result.resolved().stream().filter(resolved -> resolved.source().sourceIdentity().equals("D1")))
				.singleElement()
				.satisfies(resolved -> {
					assertThat(resolved.targetIdentity()).isEqualTo("TD1");
					assertThat(resolved.parents()).extracting(parent -> parent.source().sourceIdentity(), ParentIdentityReference::targetIdentity)
							.containsExactly(tuple("B1", "TB1"), tuple("C1", "TC1"));
				});
		assertThat(lookup.calls().stream().filter(call -> call.entityName().equals("D")).findFirst().orElseThrow()
				.sourceIdentities()).containsExactly("D1");
	}

	@Test
	void doesNotSendDuplicateSourceIdentitiesToTheProvider() {
		ScriptedLookup lookup = lookup(Map.of("Address", Map.of("A001", "T001")));

		IdentityResolutionResult result = service.resolve(
				chain("Address", "address_id"),
				batch(item("Address", "A001"), item("Address", "A001")),
				lookup,
				10);

		assertThat(lookup.calls().get(0).sourceIdentities()).containsExactly("A001");
		assertThat(result.resolved()).hasSize(1);
	}

	@Test
	void returnsResultsInAStableOrder() {
		EntityRelationshipMapping mapping = chain("Product", "product_id", "Variant", "variant_id");
		ExecutionBatchPlan plan = batch(
				item("Variant", "V2", parent("Product", "P1")),
				item("Product", "P1"),
				item("Variant", "V1", parent("Product", "P1")));
		ScriptedLookup first = lookup(Map.of(
				"Product", Map.of("P1", "TP"),
				"Variant", Map.of("V2", "TV2", "V1", "TV1")));
		ScriptedLookup second = lookup(Map.of(
				"Product", Map.of("P1", "TP"),
				"Variant", Map.of("V2", "TV2", "V1", "TV1")));

		assertThat(signature(service.resolve(mapping, plan, first, 10)))
				.isEqualTo(signature(service.resolve(mapping, plan, second, 10)));
	}

	@Test
	void doesNotCopyWorkItemPayloadFields() {
		ScriptedLookup lookup = lookup(Map.of("Address", Map.of("A001", "T001")));
		ExecutionWorkItem item = new ExecutionWorkItem(
				"Address",
				"A001",
				424242L,
				"Address",
				"payload-marker",
				List.of());

		IdentityResolutionResult result = service.resolve(chain("Address", "address_id"), batch(item), lookup, 10);

		assertThat(result.toString()).doesNotContain("payload-marker").doesNotContain("424242");
	}

	@Test
	void resolvesAProductModel() {
		ScriptedLookup lookup = lookup(Map.of(
				"Product", Map.of("P1", "TP1"),
				"Variant", Map.of("V1", "TV1"),
				"Price", Map.of("R1", "TR1")));

		IdentityResolutionResult result = service.resolve(
				chain("Product", "product_id", "Variant", "variant_id", "Price", "price_id"),
				batch(
						item("Product", "P1"),
						item("Variant", "V1", parent("Product", "P1")),
						item("Price", "R1", parent("Variant", "V1"))),
				lookup,
				10);

		assertThat(result.resolved())
				.extracting(resolved -> resolved.source().entityName(), ResolvedIdentity::targetIdentity)
				.containsExactly(tuple("Product", "TP1"), tuple("Variant", "TV1"), tuple("Price", "TR1"));
		assertThat(result.resolved().get(2).parents()).singleElement()
				.extracting(ParentIdentityReference::targetIdentity)
				.isEqualTo("TV1");
	}

	@Test
	void resolvesAnArbitraryModel() {
		ScriptedLookup lookup = lookup(Map.of(
				"Alpha", Map.of("AX", "TA"),
				"Beta", Map.of("BX", "TB"),
				"Gamma", Map.of("GX", "TG")));

		IdentityResolutionResult result = service.resolve(
				chain("Alpha", "alpha_code", "Beta", "beta_code", "Gamma", "gamma_code"),
				batch(
						item("Alpha", "AX"),
						item("Beta", "BX", parent("Alpha", "AX")),
						item("Gamma", "GX", parent("Beta", "BX"))),
				lookup,
				10);

		assertThat(result.unresolved()).isEmpty();
		assertThat(result.resolved())
				.extracting(resolved -> resolved.source().sourceIdentity(), ResolvedIdentity::targetIdentity)
				.containsExactly(tuple("AX", "TA"), tuple("BX", "TB"), tuple("GX", "TG"));
	}

	@Test
	void recordsAProviderFailureWithoutCreatingATargetIdentity() {
		ScriptedLookup lookup = lookup(Map.of("Address", Map.of("A001", "T001")));
		lookup.fail = true;

		IdentityResolutionResult result = service.resolve(
				chain("Address", "address_id"),
				batch(item("Address", "A001")),
				lookup,
				10);

		assertThat(result.resolved()).isEmpty();
		assertThat(result.unresolved()).singleElement().satisfies(unresolved -> {
			assertThat(unresolved.kind()).isEqualTo(IdentityResolutionFailureKind.LOOKUP_FAILED);
			assertThat(unresolved.message()).isEqualTo("lookup down");
		});
	}

	@Test
	void keepsResolvedAndUnresolvedIdentitiesFromAPartialLookup() {
		ScriptedLookup lookup = lookup(Map.of("Address", Map.of("A001", "T001")));

		IdentityResolutionResult result = service.resolve(
				chain("Address", "address_id"),
				batch(item("Address", "A001"), item("Address", "A002"), item("Address", "A003")),
				lookup,
				10);

		assertThat(result.resolved()).extracting(resolved -> resolved.source().sourceIdentity()).containsExactly("A001");
		assertThat(result.unresolved()).extracting(unresolved -> unresolved.source().sourceIdentity())
				.containsExactly("A002", "A003");
	}

	@Test
	void treatsAnEmptyProviderResultAsUnresolved() {
		ScriptedLookup lookup = lookup(Map.of());

		IdentityResolutionResult result = service.resolve(
				chain("Address", "address_id"),
				batch(item("Address", "A001"), item("Address", "A002")),
				lookup,
				10);

		assertThat(result.resolved()).isEmpty();
		assertThat(result.unresolved()).extracting(UnresolvedIdentity::kind)
				.containsExactly(
						IdentityResolutionFailureKind.TARGET_IDENTITY_NOT_FOUND,
						IdentityResolutionFailureKind.TARGET_IDENTITY_NOT_FOUND);
	}

	@Test
	void limitsEachLookupToTheConfiguredChunkSize() {
		ScriptedLookup lookup = lookup(Map.of());
		lookup.resolveAll = true;
		List<ExecutionWorkItem> items = new ArrayList<>();
		for (int i = 1; i <= 1000; i++) {
			items.add(item("Address", "A" + i));
		}

		service.resolve(chain("Address", "address_id"), batch(items), lookup, 100);

		assertThat(lookup.calls()).hasSize(10);
		assertThat(lookup.calls()).allSatisfy(call -> assertThat(call.sourceIdentities()).hasSize(100));
	}

	private static List<String> signature(IdentityResolutionResult result) {
		List<String> signature = new ArrayList<>();
		for (ResolvedIdentity resolved : result.resolved()) {
			signature.add("resolved:" + resolved.source().entityName() + ":" + resolved.source().sourceIdentity()
					+ "->" + resolved.targetIdentity());
		}
		for (UnresolvedIdentity unresolved : result.unresolved()) {
			signature.add("unresolved:" + unresolved.source().entityName() + ":" + unresolved.source().sourceIdentity()
					+ ":" + unresolved.kind());
		}
		return signature;
	}

	private static ExecutionBatchPlan batch(ExecutionWorkItem... items) {
		return new ExecutionBatchPlan(1, List.of(items));
	}

	private static ExecutionBatchPlan batch(List<ExecutionWorkItem> items) {
		return new ExecutionBatchPlan(1, items);
	}

	private static ExecutionWorkItem item(String entity, String source, WorkItemParent... parents) {
		return new ExecutionWorkItem(entity, source, 1, entity, source, List.of(parents));
	}

	private static WorkItemParent parent(String entity, String source) {
		return new WorkItemParent(entity, source);
	}

	private static EntityRelationshipMapping chain(String... namesAndKeys) {
		List<EntityDefinition> entities = new ArrayList<>();
		List<RelationshipDefinition> relationships = new ArrayList<>();
		for (int i = 0; i < namesAndKeys.length; i += 2) {
			entities.add(def(namesAndKeys[i], namesAndKeys[i + 1], "id"));
			if (i >= 2) {
				relationships.add(edge(namesAndKeys[i - 2], namesAndKeys[i]));
			}
		}
		return mapping(namesAndKeys[0], entities, relationships);
	}

	private static EntityRelationshipMapping mapping(
			String root,
			List<EntityDefinition> entities,
			List<RelationshipDefinition> relationships) {
		return new EntityRelationshipMapping(root, entities, relationships);
	}

	private static EntityDefinition def(String name, String sourceKey, String targetKey) {
		return new EntityDefinition(name, name.toLowerCase(), sourceKey, targetKey);
	}

	private static RelationshipDefinition edge(String parent, String child) {
		return new RelationshipDefinition(
				parent,
				child,
				RelationshipCardinality.ONE_TO_MANY,
				List.of(new RelationshipKey("parent_key", "child_key")));
	}

	private static ScriptedLookup lookup(Map<String, Map<String, String>> data) {
		return new ScriptedLookup(data);
	}

	private static final class ScriptedLookup implements TargetIdentityLookup {
		private final Map<String, Map<String, String>> data;
		private final List<Call> calls = new ArrayList<>();
		private boolean fail;
		private boolean resolveAll;

		private ScriptedLookup(Map<String, Map<String, String>> data) {
			this.data = data;
		}

		@Override
		public Map<String, String> lookup(String entityName, List<String> sourceIdentities) {
			calls.add(new Call(entityName, List.copyOf(sourceIdentities)));
			if (fail) {
				throw new IllegalStateException("lookup down");
			}
			Map<String, String> resolved = new LinkedHashMap<>();
			if (resolveAll) {
				for (String sourceIdentity : sourceIdentities) {
					resolved.put(sourceIdentity, "T-" + sourceIdentity);
				}
				return resolved;
			}
			Map<String, String> known = data.getOrDefault(entityName, Map.of());
			for (String sourceIdentity : sourceIdentities) {
				if (known.containsKey(sourceIdentity)) {
					resolved.put(sourceIdentity, known.get(sourceIdentity));
				}
			}
			return resolved;
		}

		private List<Call> calls() {
			return calls;
		}
	}

	private record Call(String entityName, List<String> sourceIdentities) {
	}
}
