package com.etp.migration.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.etp.migration.extraction.EntityRecord;
import com.etp.migration.mapping.EntityDefinition;
import com.etp.migration.mapping.EntityRelationshipMapping;
import com.etp.migration.mapping.InvalidEntityRelationshipConfigurationException;
import com.etp.migration.mapping.RelationshipCardinality;
import com.etp.migration.mapping.RelationshipDefinition;
import com.etp.migration.mapping.RelationshipKey;
import com.etp.migration.relationship.ResolvedRelationship;
import com.etp.migration.scope.LogicalMigrationScope;
import com.etp.migration.scope.LogicalMigrationScopeBuilder;
import com.etp.migration.scope.ScopedEntity;
import com.etp.migration.scope.ScopedRelationship;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ExecutionPlannerTest {

	private final LogicalMigrationScopeBuilder scopes = new LogicalMigrationScopeBuilder();
	private final ExecutionPlanner planner = new ExecutionPlanner();

	@Test
	void plansASingleRootWorkItem() {
		List<ExecutionWorkItem> items = items(plan(chain("Location", "location_id"), List.of(
				entity("Location", "L001", 4)), List.of(), 10));

		assertThat(items).containsExactly(new ExecutionWorkItem(
				"Location", "L001", 4, "Location", "L001", List.of()));
	}

	@Test
	void ordersRootBeforeChild() {
		List<ExecutionWorkItem> items = items(plan(
				chain("Location", "location_id", "Address", "address_id"),
				List.of(entity("Location", "L001", 1), entity("Address", "A001", 8)),
				List.of(link("Location", "L001", 1, "Address", "A001", 8)),
				10));

		assertThat(items)
				.extracting(ExecutionWorkItem::entityName, ExecutionWorkItem::sourceIdentity)
				.containsExactly(tuple("Location", "L001"), tuple("Address", "A001"));
		assertThat(items.get(1).parents()).containsExactly(new WorkItemParent("Location", "L001"));
	}

	@Test
	void ordersThreeLevelsByDependency() {
		List<ExecutionWorkItem> items = items(plan(
				chain("Location", "location_id", "Address", "address_id", "Site", "site_id"),
				List.of(
						entity("Site", "S001", 30),
						entity("Location", "L001", 10),
						entity("Address", "A001", 20)),
				List.of(
						link("Address", "A001", 20, "Site", "S001", 30),
						link("Location", "L001", 10, "Address", "A001", 20)),
				10));

		assertThat(items)
				.extracting(ExecutionWorkItem::entityName, ExecutionWorkItem::sourceIdentity, ExecutionWorkItem::sourceRecordNumber)
				.containsExactly(
						tuple("Location", "L001", 10L),
						tuple("Address", "A001", 20L),
						tuple("Site", "S001", 30L));
		assertThat(items.get(2).parents()).containsExactly(new WorkItemParent("Address", "A001"));
		assertThat(items.get(2).rootIdentity()).isEqualTo("L001");
	}

	@Test
	void splitsWorkItemsByBatchSize() {
		List<EntityRecord> entities = new ArrayList<>();
		List<ResolvedRelationship> relationships = new ArrayList<>();
		entities.add(entity("Location", "L001", 1));
		for (int i = 1; i <= 6; i++) {
			entities.add(entity("Address", "A00" + i, i + 1));
			relationships.add(link("Location", "L001", 1, "Address", "A00" + i, i + 1));
		}

		List<ExecutionBatchPlan> batches = plan(chain("Location", "location_id", "Address", "address_id"), entities, relationships, 3);

		assertThat(batches).extracting(ExecutionBatchPlan::batchNumber).containsExactly(1, 2, 3);
		assertThat(batches).extracting(batch -> batch.workItems().size()).containsExactly(3, 3, 1);
		assertThat(items(batches)).hasSize(7);
	}

	@Test
	void batchesALargeChildCollectionWithoutPuttingEveryChildInOneBatch() {
		List<EntityRecord> entities = new ArrayList<>();
		List<ResolvedRelationship> relationships = new ArrayList<>();
		entities.add(entity("Location", "L001", 1));
		entities.add(entity("Address", "A001", 2));
		relationships.add(link("Location", "L001", 1, "Address", "A001", 2));
		for (int i = 1; i <= 200; i++) {
			entities.add(entity("Site", "S" + i, i + 2));
			relationships.add(link("Address", "A001", 2, "Site", "S" + i, i + 2));
		}

		List<ExecutionBatchPlan> batches = plan(
				chain("Location", "location_id", "Address", "address_id", "Site", "site_id"),
				entities,
				relationships,
				50);

		assertThat(batches.size()).isGreaterThan(1);
		assertThat(batches).allSatisfy(batch -> {
			assertThat(batch.workItems()).hasSizeLessThanOrEqualTo(50);
			assertThat(batch.workItems().stream().filter(item -> item.entityName().equals("Site")).count()).isLessThan(200);
			assertThat(batch.workItems()).allSatisfy(item -> assertThat(item.rootIdentity()).isEqualTo("L001"));
		});
		List<ExecutionWorkItem> planned = items(batches);
		assertThat(planned.get(0).sourceIdentity()).isEqualTo("L001");
		assertThat(planned.get(1).sourceIdentity()).isEqualTo("A001");
		assertThat(indexOf(planned, "Site", "S1")).isGreaterThan(indexOf(planned, "Address", "A001"));
		assertThat(planned.stream().filter(item -> item.entityName().equals("Site"))).hasSize(200);
	}

	@Test
	void keepsRootIdentitySeparateAcrossScopes() {
		List<ExecutionBatchPlan> batches = plan(
				chain("Location", "location_id", "Address", "address_id"),
				List.of(
						entity("Location", "L001", 1),
						entity("Location", "L002", 2),
						entity("Address", "A001", 3),
						entity("Address", "A002", 4)),
				List.of(
						link("Location", "L001", 1, "Address", "A001", 3),
						link("Location", "L002", 2, "Address", "A002", 4)),
				1);

		assertThat(items(batches))
				.extracting(ExecutionWorkItem::sourceIdentity, ExecutionWorkItem::rootIdentity)
				.containsExactly(
						tuple("L001", "L001"),
						tuple("A001", "L001"),
						tuple("L002", "L002"),
						tuple("A002", "L002"));
		assertThat(batches).hasSize(4);
	}

	@Test
	void allowsOneBatchToContainSeveralScopes() {
		List<ExecutionBatchPlan> batches = plan(
				chain("Location", "location_id", "Address", "address_id"),
				List.of(
						entity("Location", "L001", 1),
						entity("Location", "L002", 2),
						entity("Address", "A001", 3),
						entity("Address", "A002", 4)),
				List.of(
						link("Location", "L001", 1, "Address", "A001", 3),
						link("Location", "L002", 2, "Address", "A002", 4)),
				4);

		assertThat(batches).hasSize(1);
		assertThat(batches.get(0).workItems())
				.extracting(ExecutionWorkItem::rootIdentity)
				.containsExactly("L001", "L001", "L002", "L002");
		assertThat(batches.get(0).workItems().get(1).parents())
				.containsExactly(new WorkItemParent("Location", "L001"));
		assertThat(batches.get(0).workItems().get(3).parents())
				.containsExactly(new WorkItemParent("Location", "L002"));
	}

	@Test
	void plansADiamondWithOneWorkItemForTheSharedChild() {
		EntityRelationshipMapping mapping = mapping(
				"A",
				List.of(def("A", "a_id"), def("B", "b_id"), def("C", "c_id"), def("D", "d_id")),
				List.of(edge("A", "B"), edge("A", "C"), edge("B", "D"), edge("C", "D")));
		List<ExecutionWorkItem> items = items(plan(
				mapping,
				List.of(entity("A", "A1", 1)),
				List.of(
						link("A", "A1", 1, "B", "B1", 2),
						link("A", "A1", 1, "C", "C1", 3),
						link("B", "B1", 2, "D", "D1", 4),
						link("C", "C1", 3, "D", "D1", 4)),
				10));

		assertThat(items)
				.extracting(ExecutionWorkItem::entityName, ExecutionWorkItem::sourceIdentity)
				.containsExactly(tuple("A", "A1"), tuple("B", "B1"), tuple("C", "C1"), tuple("D", "D1"));
		assertThat(items.stream().filter(item -> item.sourceIdentity().equals("D1"))).hasSize(1);
		assertThat(items.get(3).parents()).containsExactly(
				new WorkItemParent("B", "B1"),
				new WorkItemParent("C", "C1"));
	}

	@Test
	void emitsOneWorkItemWhenTheSameIdentityIsRepeated() {
		LogicalMigrationScope scope = new LogicalMigrationScope(
				"Location",
				"L001",
				List.of(
						new ScopedEntity("Location", "L001", 1),
						new ScopedEntity("Address", "A001", 105),
						new ScopedEntity("Address", "A001", 400)),
				List.of(new ScopedRelationship(
						"Location", "L001", "Address", "A001", RelationshipCardinality.ONE_TO_MANY)));

		List<ExecutionWorkItem> items = items(plan(
				chain("Location", "location_id", "Address", "address_id"),
				Stream.of(scope),
				10));

		assertThat(items)
				.extracting(ExecutionWorkItem::entityName, ExecutionWorkItem::sourceIdentity, ExecutionWorkItem::sourceRecordNumber)
				.containsExactly(tuple("Location", "L001", 1L), tuple("Address", "A001", 105L));
	}

	@Test
	void retainsParentReferencesFromResolvedRelationships() {
		List<ExecutionWorkItem> items = items(plan(
				chain("Location", "location_id", "Address", "address_id", "Site", "site_id"),
				List.of(entity("Location", "L001", 1)),
				List.of(
						link("Location", "L001", 1, "Address", "A001", 2),
						link("Address", "A001", 2, "Site", "S001", 3)),
				10));

		assertThat(items.get(1).parents()).containsExactly(new WorkItemParent("Location", "L001"));
		assertThat(items.get(1).rootEntity()).isEqualTo("Location");
		assertThat(items.get(1).rootIdentity()).isEqualTo("L001");
		assertThat(items.get(2).parents()).containsExactly(new WorkItemParent("Address", "A001"));
	}

	@Test
	void plansARootOnlyScope() {
		List<ExecutionBatchPlan> batches = plan(chain("Location", "location_id"), List.of(entity("Location", "L001", 1)), List.of(), 5);

		assertThat(batches).hasSize(1);
		assertThat(batches.get(0).workItems()).hasSize(1);
		assertThat(batches.get(0).workItems().get(0).parents()).isEmpty();
	}

	@Test
	void doesNotAddADisconnectedEntityToAnotherRoot() {
		List<ExecutionWorkItem> items = items(plan(
				chain("Location", "location_id", "Address", "address_id", "Site", "site_id"),
				List.of(
						entity("Location", "L001", 1),
						entity("Address", "A001", 2),
						entity("Site", "S999", 9)),
				List.of(link("Location", "L001", 1, "Address", "A001", 2)),
				10));

		assertThat(items)
				.extracting(ExecutionWorkItem::sourceIdentity)
				.containsExactly("L001", "A001");
	}

	@Test
	void isDeterministicForTheSameInput() {
		EntityRelationshipMapping mapping = chain("Product", "product_id", "Variant", "variant_id", "Price", "price_id");
		List<EntityRecord> entities = List.of(entity("Product", "P1", 1), entity("Variant", "V9", 2), entity("Variant", "V1", 3));
		List<ResolvedRelationship> relationships = List.of(
				link("Product", "P1", 1, "Variant", "V9", 2),
				link("Product", "P1", 1, "Variant", "V1", 3),
				link("Variant", "V1", 3, "Price", "R1", 4));

		assertThat(signature(plan(mapping, entities, relationships, 2)))
				.isEqualTo(signature(plan(mapping, entities, relationships, 2)));
	}

	@Test
	void plansAProductModelWithoutDomainSpecificCode() {
		List<ExecutionWorkItem> items = items(plan(
				chain("Product", "product_id", "Variant", "variant_id", "Price", "price_id"),
				List.of(entity("Product", "P1", 1)),
				List.of(
						link("Product", "P1", 1, "Variant", "V1", 2),
						link("Variant", "V1", 2, "Price", "PR1", 3)),
				10));

		assertThat(items)
				.extracting(ExecutionWorkItem::entityName)
				.containsExactly("Product", "Variant", "Price");
	}

	@Test
	void plansAnArbitraryModel() {
		List<ExecutionWorkItem> items = items(plan(
				chain("Alpha", "alpha_code", "Beta", "beta_code", "Gamma", "gamma_code"),
				List.of(entity("Alpha", "AX", 1)),
				List.of(
						link("Alpha", "AX", 1, "Beta", "BX", 2),
						link("Beta", "BX", 2, "Gamma", "GX", 3)),
				10));

		assertThat(items)
				.extracting(ExecutionWorkItem::entityName, ExecutionWorkItem::sourceIdentity)
				.containsExactly(tuple("Alpha", "AX"), tuple("Beta", "BX"), tuple("Gamma", "GX"));
		assertThat(items).allSatisfy(item -> assertThat(item.rootIdentity()).isEqualTo("AX"));
	}

	@Test
	void rejectsNonPositiveBatchSize() {
		EntityRelationshipMapping mapping = chain("Alpha", "alpha_code");
		assertThatThrownBy(() -> planner.plan(mapping, Stream.empty(), 0))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("batchSize must be greater than 0");
		assertThatThrownBy(() -> planner.plan(mapping, Stream.empty(), -5))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("batchSize must be greater than 0");
	}

	@Test
	void rejectsARelationshipThatTheGraphDoesNotDefine() {
		EntityRelationshipMapping mapping = mapping(
				"Location",
				List.of(def("Location", "location_id"), def("Site", "site_id")),
				List.of());

		assertThatThrownBy(() -> plan(
				mapping,
				List.of(entity("Location", "L001", 1), entity("Site", "S001", 2)),
				List.of(link("Location", "L001", 1, "Site", "S001", 2)),
				10))
				.isInstanceOf(InvalidExecutionPlanException.class)
				.hasMessage("Relationship from 'Location' to 'Site' is not defined in the entity relationship graph.");
	}

	@Test
	void rejectsACyclicGraph() {
		EntityRelationshipMapping mapping = mapping(
				"Alpha",
				List.of(def("Alpha", "alpha_id"), def("Beta", "beta_id")),
				List.of(edge("Alpha", "Beta"), edge("Beta", "Alpha")));

		assertThatThrownBy(() -> planner.plan(mapping, Stream.empty(), 10))
				.isInstanceOf(InvalidEntityRelationshipConfigurationException.class)
				.hasMessageContaining("cycle");
	}

	@Test
	void producesNoBatchesForEmptyInput() {
		List<ExecutionBatchPlan> batches = plan(chain("Alpha", "alpha_code"), Stream.empty(), 10);

		assertThat(batches).isEmpty();
	}

	@Test
	void doesNotCopySourcePayloads() {
		EntityRecord root = new EntityRecord("Location", 1, "L001", Map.of("blob", "payload-marker"));
		EntityRecord child = new EntityRecord("Address", 2, "A001", Map.of("blob", "payload-marker", "notes", "x".repeat(64)));
		List<ExecutionWorkItem> items = items(plan(
				chain("Location", "location_id", "Address", "address_id"),
				List.of(root, child),
				List.of(link("Location", "L001", 1, "Address", "A001", 2)),
				10));

		assertThat(items).allSatisfy(item -> assertThat(item.toString()).doesNotContain("payload-marker"));
	}

	@Test
	void splitsOneScopeAcrossBatchesWhileKeepingTheSameRoot() {
		List<EntityRecord> entities = new ArrayList<>();
		List<ResolvedRelationship> relationships = new ArrayList<>();
		entities.add(entity("Alpha", "AX", 1));
		for (int i = 1; i <= 9; i++) {
			entities.add(entity("Beta", "B" + i, i + 1));
			relationships.add(link("Alpha", "AX", 1, "Beta", "B" + i, i + 1));
		}

		List<ExecutionBatchPlan> batches = plan(chain("Alpha", "alpha_code", "Beta", "beta_code"), entities, relationships, 3);

		assertThat(batches).hasSize(4);
		assertThat(batches).allSatisfy(batch ->
				assertThat(batch.workItems()).allSatisfy(item -> {
					assertThat(item.rootEntity()).isEqualTo("Alpha");
					assertThat(item.rootIdentity()).isEqualTo("AX");
				}));
		assertThat(items(batches)).hasSize(10);
	}

	@Test
	void emitsTheFirstBatchBeforeReadingEveryScope() {
		EntityRelationshipMapping mapping = chain("Alpha", "alpha_code", "Beta", "beta_code", "Gamma", "gamma_code");
		AtomicInteger opened = new AtomicInteger();
		Stream<LogicalMigrationScope> incoming = IntStream.rangeClosed(1, 15).mapToObj(index -> {
			opened.incrementAndGet();
			return new LogicalMigrationScope(
					"Alpha",
					"A" + index,
					List.of(
							new ScopedEntity("Alpha", "A" + index, 1),
							new ScopedEntity("Beta", "B" + index, 2),
							new ScopedEntity("Gamma", "G" + index, 3)),
					List.of(
							new ScopedRelationship("Alpha", "A" + index, "Beta", "B" + index, RelationshipCardinality.ONE_TO_MANY),
							new ScopedRelationship("Beta", "B" + index, "Gamma", "G" + index, RelationshipCardinality.ONE_TO_MANY)));
		});

		try (Stream<ExecutionBatchPlan> planned = planner.plan(mapping, incoming, 2)) {
			ExecutionBatchPlan first = planned.iterator().next();
			assertThat(opened).hasValue(1);
			assertThat(first.batchNumber()).isEqualTo(1);
			assertThat(first.workItems())
					.extracting(ExecutionWorkItem::entityName)
					.containsExactly("Alpha", "Beta");
		}
	}

	private List<ExecutionBatchPlan> plan(
			EntityRelationshipMapping mapping,
			List<EntityRecord> entities,
			List<ResolvedRelationship> relationships,
			int batchSize) {
		try (Stream<LogicalMigrationScope> built = scopes.build(mapping, entities.stream(), relationships.stream())) {
			List<LogicalMigrationScope> materialized = built.toList();
			return plan(mapping, materialized.stream(), batchSize);
		}
	}

	private List<ExecutionBatchPlan> plan(
			EntityRelationshipMapping mapping,
			Stream<LogicalMigrationScope> scopeStream,
			int batchSize) {
		try (Stream<ExecutionBatchPlan> planned = planner.plan(mapping, scopeStream, batchSize)) {
			return planned.toList();
		}
	}

	private static List<ExecutionWorkItem> items(List<ExecutionBatchPlan> batches) {
		return batches.stream().flatMap(batch -> batch.workItems().stream()).toList();
	}

	private static int indexOf(List<ExecutionWorkItem> items, String entityName, String sourceIdentity) {
		for (int i = 0; i < items.size(); i++) {
			ExecutionWorkItem item = items.get(i);
			if (item.entityName().equals(entityName) && item.sourceIdentity().equals(sourceIdentity)) {
				return i;
			}
		}
		return -1;
	}

	private static List<String> signature(List<ExecutionBatchPlan> batches) {
		List<String> signature = new ArrayList<>();
		for (ExecutionBatchPlan batch : batches) {
			signature.add("batch:" + batch.batchNumber());
			for (ExecutionWorkItem item : batch.workItems()) {
				signature.add(item.rootIdentity() + "/" + item.entityName() + ":" + item.sourceIdentity());
			}
		}
		return signature;
	}

	private static EntityRelationshipMapping chain(String... namesAndKeys) {
		List<EntityDefinition> entities = new ArrayList<>();
		List<RelationshipDefinition> relationships = new ArrayList<>();
		for (int i = 0; i < namesAndKeys.length; i += 2) {
			entities.add(def(namesAndKeys[i], namesAndKeys[i + 1]));
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

	private static EntityDefinition def(String name, String sourceKey) {
		return new EntityDefinition(name, name.toLowerCase(), sourceKey, "id");
	}

	private static RelationshipDefinition edge(String parent, String child) {
		return new RelationshipDefinition(
				parent,
				child,
				RelationshipCardinality.ONE_TO_MANY,
				List.of(new RelationshipKey(parent.toLowerCase() + "_id", parent.toLowerCase() + "_id")));
	}

	private static EntityRecord entity(String name, String identity, long recordNumber) {
		return new EntityRecord(name, recordNumber, identity, Map.of());
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
