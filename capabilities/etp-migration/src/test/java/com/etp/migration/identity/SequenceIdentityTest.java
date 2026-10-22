package com.etp.migration.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.etp.migration.entity.MigrationIdentityAllocation;
import com.etp.migration.mapping.EntityDefinition;
import com.etp.migration.mapping.EntityRelationshipConfiguration;
import com.etp.migration.mapping.EntityRelationshipMapping;
import com.etp.migration.mapping.InvalidEntityRelationshipConfigurationException;
import com.etp.migration.mapping.RelationshipCardinality;
import com.etp.migration.mapping.RelationshipDefinition;
import com.etp.migration.mapping.RelationshipKey;
import com.etp.migration.mapping.TargetIdentitySpec;
import com.etp.migration.planning.ExecutionBatchPlan;
import com.etp.migration.planning.ExecutionWorkItem;
import com.etp.migration.planning.WorkItemParent;
import com.etp.migration.repository.MigrationIdentityAllocationRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SequenceIdentityTest {

	private final UUID runId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
	private final List<MigrationIdentityAllocation> stored = new ArrayList<>();
	private final MemoryAllocator allocator = new MemoryAllocator();

	@Mock
	private MigrationIdentityAllocationRepository repository;

	private GeneratedTargetIdentityService service;

	@BeforeEach
	void setUp() {
		stored.clear();
		allocator.reset();
		lenient().when(repository.findByMigrationRunIdAndEntityNameAndSourceIdentityIn(any(), any(), any()))
				.thenAnswer(invocation -> {
					String entityName = invocation.getArgument(1);
					Collection<String> sources = invocation.getArgument(2);
					return stored.stream()
							.filter(row -> entityName.equals(row.getEntityName()) && sources.contains(row.getSourceIdentity()))
							.toList();
				});
		lenient().doAnswer(invocation -> {
			Iterable<MigrationIdentityAllocation> rows = invocation.getArgument(0);
			for (MigrationIdentityAllocation row : rows) {
				stored.add(row);
			}
			return null;
		}).when(repository).insertAll(any());
		service = new GeneratedTargetIdentityService(repository, allocator, (schema, table, column, prefix, length) -> 0L);
	}

	@Test
	void formatsPrefixAndSequenceLengthWithoutCopyingTheSourceIdentity() {
		assertThat(PrefixSequenceFormatter.format("LOC-", 9, 1)).isEqualTo("LOC-000000001");
		assertThat(PrefixSequenceFormatter.format("ADDR-", 10, 1)).isEqualTo("ADDR-0000000001");
		assertThat(PrefixSequenceFormatter.format("GEO-LOC-", 9, 1)).isEqualTo("GEO-LOC-000000001");
		assertThat(PrefixSequenceFormatter.format("GEO-", 9, 1)).isEqualTo("GEO-000000001");
		assertThat(PrefixSequenceFormatter.format("GEOM-", 9, 1)).isEqualTo("GEOM-000000001");
		assertThat(PrefixSequenceFormatter.format("CUST-", 8, 1)).isEqualTo("CUST-00000001");
		assertThat(PrefixSequenceFormatter.format("LOC-", 9, 1251)).isEqualTo("LOC-000001251");
		assertThat(PrefixSequenceFormatter.format("LOC-", 9, 1)).isNotEqualTo("ADDR001");
	}

	@Test
	void prefixSourceValueAppendsTheSourceIdentity() {
		assertThat(PrefixSequenceFormatter.prefixSourceValue("ORD-", "1001")).isEqualTo("ORD-1001");
	}

	@Test
	void configurationDescribesFormatOnly() {
		EntityRelationshipMapping mapping = EntityRelationshipConfiguration.parse(projectConfiguration());
		TargetIdentitySpec customer = mapping.entities().get(0).targetIdentity();
		TargetIdentitySpec order = mapping.entities().get(1).targetIdentity();
		assertThat(customer.strategy()).isEqualTo("PREFIX_SEQUENCE");
		assertThat(customer.prefix()).isEqualTo("CUST-");
		assertThat(customer.sequenceLength()).isEqualTo(8);
		assertThat(order.strategy()).isEqualTo("PREFIX_SOURCE_VALUE");
		assertThat(order.prefix()).isEqualTo("ORD-");
		assertThat(order.sequenceLength()).isNull();
		Map<String, Object> withStart = new LinkedHashMap<>(projectConfiguration());
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> entities = (List<Map<String, Object>>) withStart.get("entities");
		Map<String, Object> identity = new LinkedHashMap<>((Map<String, Object>) entities.get(0).get("targetIdentity"));
		identity.put("start", 1);
		entities.get(0).put("targetIdentity", identity);
		assertThatThrownBy(() -> EntityRelationshipConfiguration.parse(withStart))
				.isInstanceOf(InvalidEntityRelationshipConfigurationException.class)
				.hasMessageContaining("start");
	}

	@Test
	void anotherProjectUsesTheSameAllocatorAndFormatter() {
		Map<String, String> customers = service.assign(
				runId,
				"Customer",
				List.of("C1", "C2"),
				new TargetIdentitySpec("PREFIX_SEQUENCE", "CUST-", 8),
				null,
				"customer",
				"id");
		assertThat(customers).containsEntry("C1", "CUST-00000001").containsEntry("C2", "CUST-00000002");
		Map<String, String> orders = service.assign(
				runId,
				"Order",
				List.of("1001"),
				new TargetIdentitySpec("PREFIX_SOURCE_VALUE", "ORD-", null),
				null,
				"order",
				"id");
		assertThat(orders).containsEntry("1001", "ORD-1001");
		assertThat(allocator.reserved).doesNotContain("ORD-");
	}

	@Test
	void rangeReservationContinuesAndRetryReusesTheSameIdentity() {
		allocator.floor("PFX-", 1250L);
		Map<String, String> first = service.assign(
				runId,
				"Sample",
				List.of("S1", "S2"),
				new TargetIdentitySpec("PREFIX_SEQUENCE", "PFX-", 9),
				"app",
				"sample",
				"id");
		assertThat(first).containsEntry("S1", "PFX-000001251").containsEntry("S2", "PFX-000001252");
		Map<String, String> again = service.assign(
				runId,
				"Sample",
				List.of("S2", "S1"),
				new TargetIdentitySpec("PREFIX_SEQUENCE", "PFX-", 9),
				"app",
				"sample",
				"id");
		assertThat(again).containsEntry("S1", "PFX-000001251").containsEntry("S2", "PFX-000001252");
		assertThat(allocator.current("PFX-")).isEqualTo(1252L);
		Map<String, String> next = service.assign(
				runId,
				"Sample",
				List.of("S3"),
				new TargetIdentitySpec("PREFIX_SEQUENCE", "PFX-", 9),
				"app",
				"sample",
				"id");
		assertThat(next).containsEntry("S3", "PFX-000001253");
	}

	@Test
	void twoPrefixesOnTheSameTableDoNotShareARange() {
		allocator.floor("LOC-", 1250L);
		allocator.floor("GEO-LOC-", 10L);
		assertThat(service.assign(
				runId,
				"AddressLocation",
				List.of("ADDR001"),
				new TargetIdentitySpec("PREFIX_SEQUENCE", "LOC-", 9),
				"uam",
				"location",
				"id")).containsEntry("ADDR001", "LOC-000001251");
		assertThat(service.assign(
				runId,
				"GeographicPointLocation",
				List.of("GEO001"),
				new TargetIdentitySpec("PREFIX_SEQUENCE", "GEO-LOC-", 9),
				"uam",
				"location",
				"id")).containsEntry("GEO001", "GEO-LOC-000000011");
	}

	@Test
	void existingLookupWins() {
		TargetIdentityLookup lookup = GeneratedTargetIdentityLookup.decorate(
				service,
				runId,
				uamMapping(),
				Map.of("target", Map.of("schema", "uam")),
				(entityName, sourceIdentities) -> "AddressLocation".equals(entityName)
						? Map.of("ADDR001", "KEEP-ME")
						: Map.of());
		assertThat(lookup.lookup("AddressLocation", List.of("ADDR001", "ADDR002")))
				.containsEntry("ADDR001", "KEEP-ME")
				.containsEntry("ADDR002", "LOC-000000001");
	}

	@Test
	void absentTargetIdentityLeavesTheExistingLookupUntouched() {
		TargetIdentityLookup delegate = (entityName, sourceIdentities) -> Map.of("ADDR001", "KEEP-ME");
		EntityRelationshipMapping mapping = new EntityRelationshipMapping(
				"Party",
				List.of(new EntityDefinition("Party", "party", "party_id", "id")),
				List.of());
		assertThat(GeneratedTargetIdentityLookup.decorate(null, null, mapping, Map.of(), delegate)).isSameAs(delegate);
	}

	@Test
	void generatedParentIdentitiesAreVisibleToChildren() {
		TargetIdentityLookup lookup = GeneratedTargetIdentityLookup.decorate(
				service,
				runId,
				uamMapping(),
				Map.of("target", Map.of("schema", "uam")),
				(entityName, sourceIdentities) -> Map.of());
		IdentityResolutionResult result = new IdentityResolutionService().resolve(
				uamMapping(),
				new ExecutionBatchPlan(1, workItems()),
				lookup,
				100);
		assertThat(result.unresolved()).isEmpty();
		assertThat(target(result, "AddressLocation", "ADDR001")).isEqualTo("LOC-000000001");
		assertThat(target(result, "GeographicAddress", "ADDR001")).isEqualTo("ADDR-0000000001");
		assertThat(target(result, "GeographicPointLocation", "GEO001")).isEqualTo("GEO-LOC-000000001");
		assertThat(target(result, "GeographicLocation", "GEO001")).isEqualTo("GEO-000000001");
		assertThat(target(result, "LocationGeometry", "GEO001")).isEqualTo("GEOM-000000001");
		assertThat(parent(resolved(result, "GeographicAddress", "ADDR001"), "AddressLocation")).isEqualTo("LOC-000000001");
		assertThat(parent(resolved(result, "GeographicAddress", "ADDR001"), "GeographicLocation")).isEqualTo("GEO-000000001");
		assertThat(parent(resolved(result, "GeographicLocation", "GEO001"), "GeographicPointLocation"))
				.isEqualTo("GEO-LOC-000000001");
		assertThat(parent(resolved(result, "LocationGeometry", "GEO001"), "GeographicLocation"))
				.isEqualTo("GEO-000000001");
	}

	private static String target(IdentityResolutionResult result, String entityName, String sourceIdentity) {
		return resolved(result, entityName, sourceIdentity).targetIdentity();
	}

	private static ResolvedIdentity resolved(IdentityResolutionResult result, String entityName, String sourceIdentity) {
		return result.resolved().stream()
				.filter(identity -> entityName.equals(identity.source().entityName())
						&& sourceIdentity.equals(identity.source().sourceIdentity()))
				.findFirst()
				.orElseThrow();
	}

	private static String parent(ResolvedIdentity identity, String parentEntity) {
		return identity.parents().stream()
				.filter(parent -> parentEntity.equals(parent.source().entityName()))
				.map(ParentIdentityReference::targetIdentity)
				.findFirst()
				.orElseThrow();
	}

	private static List<ExecutionWorkItem> workItems() {
		return List.of(
				item("AddressLocation", "ADDR001", List.of()),
				item("GeographicPointLocation", "GEO001", List.of(new WorkItemParent("AddressLocation", "ADDR001"))),
				item("GeographicLocation", "GEO001", List.of(new WorkItemParent("GeographicPointLocation", "GEO001"))),
				item("GeographicAddress", "ADDR001", List.of(
						new WorkItemParent("AddressLocation", "ADDR001"),
						new WorkItemParent("GeographicLocation", "GEO001"))),
				item("LocationGeometry", "GEO001", List.of(
						new WorkItemParent("GeographicPointLocation", "GEO001"),
						new WorkItemParent("GeographicLocation", "GEO001"))));
	}

	private static ExecutionWorkItem item(String entity, String source, List<WorkItemParent> parents) {
		return new ExecutionWorkItem(entity, source, 1L, "AddressLocation", "ADDR001", parents);
	}

	private static EntityRelationshipMapping uamMapping() {
		return new EntityRelationshipMapping(
				"AddressLocation",
				List.of(
						defined("AddressLocation", "location", "address_id", "LOC-", 9),
						defined("GeographicPointLocation", "location", "geo_id", "GEO-LOC-", 9),
						defined("GeographicAddress", "geographic_address", "address_id", "ADDR-", 10),
						defined("GeographicLocation", "geographic_location", "geo_id", "GEO-", 9),
						defined("LocationGeometry", "location_geometry", "geo_id", "GEOM-", 9)),
				List.of(
						edge("AddressLocation", "GeographicAddress", "address_id", "address_id"),
						edge("AddressLocation", "GeographicPointLocation", "geo_id", "geo_id"),
						edge("GeographicPointLocation", "GeographicLocation", "geo_id", "geo_id"),
						edge("GeographicPointLocation", "LocationGeometry", "geo_id", "geo_id"),
						edge("GeographicLocation", "GeographicAddress", "geo_id", "geo_id"),
						edge("GeographicLocation", "LocationGeometry", "geo_id", "geo_id")));
	}

	private static EntityDefinition defined(String name, String table, String sourceKey, String prefix, int length) {
		return new EntityDefinition(
				name,
				table,
				sourceKey,
				"id",
				new TargetIdentitySpec("PREFIX_SEQUENCE", prefix, length));
	}

	private static RelationshipDefinition edge(String parent, String child, String parentField, String childField) {
		return new RelationshipDefinition(
				parent,
				child,
				RelationshipCardinality.ONE_TO_ONE,
				List.of(new RelationshipKey(parentField, childField)));
	}

	private static Map<String, Object> projectConfiguration() {
		Map<String, Object> customerIdentity = new LinkedHashMap<>();
		customerIdentity.put("strategy", "PREFIX_SEQUENCE");
		customerIdentity.put("prefix", "CUST-");
		customerIdentity.put("sequenceLength", 8);
		Map<String, Object> orderIdentity = new LinkedHashMap<>();
		orderIdentity.put("strategy", "PREFIX_SOURCE_VALUE");
		orderIdentity.put("prefix", "ORD-");
		Map<String, Object> customer = new LinkedHashMap<>();
		customer.put("name", "Customer");
		customer.put("targetEntity", "customer");
		customer.put("sourceKey", "customer_number");
		customer.put("targetKey", "id");
		customer.put("targetIdentity", customerIdentity);
		Map<String, Object> order = new LinkedHashMap<>();
		order.put("name", "Order");
		order.put("targetEntity", "order");
		order.put("sourceKey", "order_number");
		order.put("targetKey", "id");
		order.put("targetIdentity", orderIdentity);
		Map<String, Object> configuration = new LinkedHashMap<>();
		configuration.put("modelType", "ENTITY_RELATIONSHIP");
		configuration.put("rootEntity", "Customer");
		configuration.put("entities", new ArrayList<>(List.of(customer, order)));
		configuration.put("relationships", List.of(Map.of(
				"parent", "Customer",
				"child", "Order",
				"cardinality", "ONE_TO_MANY",
				"keys", List.of(Map.of("parentField", "customer_number", "childField", "customer_number")))));
		return configuration;
	}

	private static final class MemoryAllocator implements TargetIdentityAllocator {

		private final Map<String, Long> current = new HashMap<>();
		private final Map<String, Boolean> established = new HashMap<>();
		private final Map<String, Long> floors = new HashMap<>();
		private final List<String> reserved = new ArrayList<>();

		void reset() {
			current.clear();
			established.clear();
			floors.clear();
			reserved.clear();
		}

		void floor(String namespace, long value) {
			floors.put(namespace, value);
		}

		long current(String namespace) {
			return current.getOrDefault(namespace, 0L);
		}

		@Override
		public synchronized long reserve(String namespace, int count, IdentityFloor floor) {
			long value = current.getOrDefault(namespace, 0L);
			if (!established.getOrDefault(namespace, false)) {
				long existing = floors.getOrDefault(namespace, floor == null ? 0L : floor.existingMaximum());
				if (existing > value) {
					value = existing;
				}
				established.put(namespace, true);
			}
			long start = value + 1;
			current.put(namespace, value + count);
			reserved.add(namespace);
			return start;
		}
	}
}
