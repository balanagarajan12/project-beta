package com.etp.migration.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.etp.migration.entity.MigrationMappingRule;
import com.etp.migration.entity.MigrationRecordStatus;
import com.etp.migration.entity.TransformationType;
import com.etp.migration.processing.MigrationRecordProcessor;
import com.etp.migration.processing.SourceRecord;
import com.etp.migration.transformation.MappingEngine;
import com.etp.migration.transformation.TransformationEngine;
import com.etp.migration.validation.ValidationEngine;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class EntityRelationshipConfigurationTest {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	@Test
	void acceptsSingleEntityWithoutRelationships() {
		EntityRelationshipMapping mapping = EntityRelationshipConfiguration.parse(model(
				"Invoice",
				List.of(entity("Invoice", "invoice", "invoice_id", "id")),
				null));

		assertThat(mapping.rootEntity()).isEqualTo("Invoice");
		assertThat(mapping.entities()).containsExactly(new EntityDefinition("Invoice", "invoice", "invoice_id", "id"));
		assertThat(mapping.relationships()).isEmpty();
		assertThat(mapping.graph().outgoing("Invoice")).isEmpty();
	}

	@Test
	void acceptsParentChildRelationship() {
		EntityRelationshipMapping mapping = EntityRelationshipConfiguration.parse(model(
				"Order",
				List.of(
						entity("OrderItem", "order_item", "order_item_id", "id"),
						entity("Order", "order", "order_id", "id")),
				List.of(relationship("Order", "OrderItem", "ONE_TO_MANY", "order_id", "order_id"))));

		assertThat(mapping.graph().outgoing("Order")).singleElement()
				.extracting(RelationshipDefinition::child, RelationshipDefinition::cardinality)
				.containsExactly("OrderItem", RelationshipCardinality.ONE_TO_MANY);
		assertThat(mapping.graph().outgoing("Order").get(0).keys())
				.containsExactly(new RelationshipKey("order_id", "order_id"));
	}

	@ParameterizedTest
	@MethodSource("hierarchies")
	void acceptsThreeLevelHierarchiesIndependentOfEntityOrder(Hierarchy hierarchy) {
		EntityRelationshipMapping mapping = EntityRelationshipConfiguration.parse(hierarchy.configuration());

		assertThat(mapping.rootEntity()).isEqualTo(hierarchy.root());
		assertThat(walk(mapping)).containsExactlyElementsOf(hierarchy.chain());
		assertThat(mapping.entities()).extracting(EntityDefinition::name)
				.containsExactlyElementsOf(hierarchy.declaredOrder());
		assertThat(mapping.relationships()).allSatisfy(relationship -> {
			assertThat(relationship.cardinality()).isEqualTo(RelationshipCardinality.ONE_TO_MANY);
			assertThat(relationship.keys()).isNotEmpty();
		});
	}

	@Test
	void acceptsOneToOneAndManyToOne() {
		EntityRelationshipMapping mapping = EntityRelationshipConfiguration.parse(model(
				"Alpha",
				List.of(
						entity("Alpha", "alpha", "alpha_id", "id"),
						entity("Beta", "beta", "beta_id", "id"),
						entity("Gamma", "gamma", "gamma_id", "id")),
				List.of(
						relationship("Alpha", "Beta", "ONE_TO_ONE", "alpha_id", "alpha_id"),
						relationship("Gamma", "Alpha", "MANY_TO_ONE", "alpha_id", "alpha_id"))));

		assertThat(mapping.graph().outgoing("Alpha").get(0).cardinality()).isEqualTo(RelationshipCardinality.ONE_TO_ONE);
		assertThat(mapping.graph().outgoing("Gamma").get(0).cardinality()).isEqualTo(RelationshipCardinality.MANY_TO_ONE);
	}

	@Test
	void rejectsMissingRootEntity() {
		Map<String, Object> configuration = model(
				"Invoice",
				List.of(entity("Invoice", "invoice", "invoice_id", "id")),
				List.of());
		configuration.remove("rootEntity");

		assertThatThrownBy(() -> EntityRelationshipConfiguration.parse(configuration))
				.isInstanceOf(InvalidEntityRelationshipConfigurationException.class)
				.hasMessage("rootEntity is required for ENTITY_RELATIONSHIP");
	}

	@Test
	void rejectsUnknownRootEntity() {
		assertInvalid(
				model("Missing", List.of(entity("Invoice", "invoice", "invoice_id", "id")), List.of()),
				"rootEntity 'Missing' does not match a configured entity");
	}

	@Test
	void rejectsDuplicateEntityName() {
		assertInvalid(
				model(
						"Customer",
						List.of(
								entity("Customer", "customer", "customer_id", "id"),
								entity("Customer", "other", "other_id", "id")),
						List.of()),
				"Duplicate entity name 'Customer'");
	}

	@Test
	void rejectsMissingTargetEntity() {
		Map<String, Object> entity = new LinkedHashMap<>();
		entity.put("name", "Customer");
		entity.put("sourceKey", "customer_id");
		entity.put("targetKey", "id");

		assertInvalid(
				model("Customer", List.of(entity), List.of()),
				"Entity 'Customer' requires targetEntity");
	}

	@Test
	void acceptsNullSourceKeyWithoutTreatingItAsAFieldName() {
		Map<String, Object> entity = new LinkedHashMap<>();
		entity.put("entity", "Product");
		entity.put("targetEntity", "product");
		entity.put("sourceKey", null);
		entity.put("targetKey", "sku");

		EntityRelationshipMapping mapping = EntityRelationshipConfiguration.parse(
				model("Product", List.of(entity), List.of()));

		assertThat(mapping.entities()).singleElement()
				.extracting(EntityDefinition::name, EntityDefinition::sourceKey, EntityDefinition::targetKey)
				.containsExactly("Product", null, "sku");
	}

	@Test
	void literalNullSourceKeyIsNotAFieldName() {
		Map<String, Object> entity = new LinkedHashMap<>();
		entity.put("name", "Product");
		entity.put("targetEntity", "product");
		entity.put("sourceKey", "null");
		entity.put("targetKey", "id");

		EntityRelationshipMapping mapping = EntityRelationshipConfiguration.parse(
				model("Product", List.of(entity), List.of()));

		assertThat(mapping.entities().get(0).sourceKey()).isNull();
	}

	@Test
	void rejectsMissingTargetKey() {
		Map<String, Object> entity = new LinkedHashMap<>();
		entity.put("name", "Product");
		entity.put("targetEntity", "product");
		entity.put("sourceKey", "product_id");

		assertInvalid(
				model("Product", List.of(entity), List.of()),
				"Entity 'Product' requires targetKey");
	}

	@Test
	void rejectsUnknownParent() {
		assertInvalid(
				model(
						"Customer",
						List.of(
								entity("Customer", "customer", "customer_id", "id"),
								entity("Account", "account", "account_id", "id")),
						List.of(relationship("Accountt", "Account", "ONE_TO_MANY", "customer_id", "customer_id"))),
				"Relationship references unknown entity 'Accountt'");
	}

	@Test
	void geographicModelResolvesAddressLocationAfterEntitiesAreRegistered() throws Exception {
		String json = """
				{
				  "modelType": "ENTITY_RELATIONSHIP",
				  "rootEntity": "AddressLocation",
				  "entities": [
				    {"name": "GeographicSubAddress", "targetEntity": "geographic_sub_address", "sourceKey": "sub_address_id", "targetKey": "id"},
				    {"name": "LocationGeometry", "targetEntity": "location_geometry", "sourceKey": "geo_id", "targetKey": "id"},
				    {"name": "GeographicAddress", "targetEntity": "geographic_address", "sourceKey": "address_id", "targetKey": "id"},
				    {"name": "GeographicLocation", "targetEntity": "geographic_location", "sourceKey": "geo_id", "targetKey": "id"},
				    {"name": "GeographicPointLocation", "targetEntity": "location", "sourceKey": "geo_id", "targetKey": "id"},
				    {"name": "ExternalIdentifier", "targetEntity": "external_identifier", "sourceKey": "external_id", "targetKey": "id"},
				    {"name": "AddressLocation", "targetEntity": "location", "sourceKey": "address_id", "targetKey": "id"}
				  ],
				  "relationships": [
				    {"parent": "AddressLocation", "child": "ExternalIdentifier", "cardinality": "ONE_TO_MANY", "targetForeignKey": {"column": "entity_id", "referencesColumn": "id"}},
				    {"parent": "AddressLocation", "child": "GeographicAddress", "cardinality": "ONE_TO_ONE", "targetForeignKey": {"column": "location_id", "referencesColumn": "id"}},
				    {"parent": "GeographicLocation", "child": "GeographicAddress", "cardinality": "ONE_TO_ONE", "targetForeignKey": {"column": "geographic_location_id", "referencesColumn": "id"}},
				    {"parent": "GeographicAddress", "child": "GeographicSubAddress", "cardinality": "ONE_TO_MANY", "targetForeignKey": {"column": "address_id", "referencesColumn": "location_id"}},
				    {"parent": "GeographicLocation", "child": "GeographicSubAddress", "cardinality": "ONE_TO_MANY", "targetForeignKey": {"column": "geographic_location_id", "referencesColumn": "id"}},
				    {"parent": "GeographicPointLocation", "child": "GeographicLocation", "cardinality": "ONE_TO_ONE", "targetForeignKey": {"column": "location_id", "referencesColumn": "id"}},
				    {"parent": "GeographicLocation", "child": "LocationGeometry", "cardinality": "ONE_TO_ONE", "targetForeignKey": {"column": "location_id", "referencesColumn": "id"}}
				  ]
				}
				""";
		Map<String, Object> persisted = OBJECT_MAPPER.readValue(json, new TypeReference<>() { });
		EntityRelationshipMapping mapping = EntityRelationshipConfiguration.parse(persisted);

		assertThat(mapping.entities()).extracting(EntityDefinition::name).containsExactly(
				"GeographicSubAddress",
				"LocationGeometry",
				"GeographicAddress",
				"GeographicLocation",
				"GeographicPointLocation",
				"ExternalIdentifier",
				"AddressLocation");
		assertThat(mapping.entities()).filteredOn(entity -> "location".equals(entity.targetEntity()))
				.extracting(EntityDefinition::name)
				.containsExactly("GeographicPointLocation", "AddressLocation");
		assertThat(mapping.relationships()).hasSize(7);
		assertThat(mapping.graph().outgoing("AddressLocation"))
				.extracting(RelationshipDefinition::child)
				.contains("GeographicAddress");
		assertThat(mapping.relationships())
				.filteredOn(relationship -> "GeographicAddress".equals(relationship.child())
						&& "AddressLocation".equals(relationship.parent()))
				.singleElement()
				.extracting(relationship -> relationship.targetForeignKey().referencesColumn())
				.isEqualTo("id");
	}

	@Test
	void rejectsUnknownChild() {
		assertInvalid(
				model(
						"Product",
						List.of(
								entity("Product", "product", "product_id", "id"),
								entity("Variant", "variant", "variant_id", "id")),
						List.of(relationship("Product", "Varian", "ONE_TO_MANY", "product_id", "product_id"))),
				"Relationship references unknown entity 'Varian'");
	}

	@Test
	void rejectsSelfReference() {
		assertInvalid(
				model(
						"Customer",
						List.of(entity("Customer", "customer", "customer_id", "id")),
						List.of(relationship("Customer", "Customer", "ONE_TO_MANY", "customer_id", "customer_id"))),
				"Relationship from 'Customer' to 'Customer' is a self-reference, which is not supported");
	}

	@Test
	void acceptsTargetForeignKeyWithoutSourceKeys() {
		Map<String, Object> relationship = new LinkedHashMap<>();
		relationship.put("parent", "GeographicAddress");
		relationship.put("child", "GeographicSubAddress");
		relationship.put("cardinality", "ONE_TO_MANY");
		relationship.put("targetForeignKey", Map.of("column", "address_id", "referencesColumn", "location_id"));

		EntityRelationshipMapping mapping = EntityRelationshipConfiguration.parse(model(
				"GeographicAddress",
				List.of(
						entity("GeographicAddress", "geographic_address", "address_id", "location_id"),
						entity("GeographicSubAddress", "geographic_sub_address", null, "id")),
				List.of(relationship)));

		RelationshipDefinition parsed = mapping.relationships().get(0);
		assertThat(parsed.keys()).isEmpty();
		assertThat(parsed.targetForeignKey()).isEqualTo(new TargetForeignKey("address_id", "location_id"));
		assertThat(mapping.entities().get(1).sourceKey()).isNull();
	}

	@Test
	void rejectsTargetForeignKeyThatDoesNotMatchTheDatabaseShape() {
		Map<String, Object> relationship = new LinkedHashMap<>();
		relationship.put("parent", "GeographicAddress");
		relationship.put("child", "GeographicSubAddress");
		relationship.put("cardinality", "ONE_TO_MANY");
		relationship.put("targetForeignKey", Map.of("column", "address id", "referencesColumn", "location_id"));

		assertInvalid(
				model(
						"GeographicAddress",
						List.of(
								entity("GeographicAddress", "geographic_address", "address_id", "location_id"),
								entity("GeographicSubAddress", "geographic_sub_address", "sub_id", "id")),
						List.of(relationship)),
				"Relationship from 'GeographicAddress' to 'GeographicSubAddress' targetForeignKey.column is invalid");
	}

	@Test
	void rejectsUnsupportedCardinality() {
		assertInvalid(
				model(
						"Order",
						List.of(
								entity("Order", "order", "order_id", "id"),
								entity("OrderItem", "order_item", "order_item_id", "id")),
						List.of(relationship("Order", "OrderItem", "MANY_TO_MANY", "order_id", "order_id"))),
				"Unsupported cardinality 'MANY_TO_MANY'. Supported values are ONE_TO_ONE, ONE_TO_MANY, and MANY_TO_ONE");
	}

	@Test
	void rejectsRelationshipWithoutKeys() {
		Map<String, Object> relationship = new LinkedHashMap<>();
		relationship.put("parent", "Order");
		relationship.put("child", "OrderItem");
		relationship.put("cardinality", "ONE_TO_MANY");
		relationship.put("keys", List.of());

		assertInvalid(
				model(
						"Order",
						List.of(
								entity("Order", "order", "order_id", "id"),
								entity("OrderItem", "order_item", "order_item_id", "id")),
						List.of(relationship)),
				"Relationship from 'Order' to 'OrderItem' requires at least one key");
	}

	@Test
	void rejectsInvalidRelationshipKey() {
		Map<String, Object> key = new LinkedHashMap<>();
		key.put("parentField", " ");
		key.put("childField", "order_id");
		Map<String, Object> relationship = new LinkedHashMap<>();
		relationship.put("parent", "Order");
		relationship.put("child", "OrderItem");
		relationship.put("cardinality", "ONE_TO_MANY");
		relationship.put("keys", List.of(key));

		assertInvalid(
				model(
						"Order",
						List.of(
								entity("Order", "order", "order_id", "id"),
								entity("OrderItem", "order_item", "order_item_id", "id")),
						List.of(relationship)),
				"Relationship from 'Order' to 'OrderItem' requires parentField");
	}

	@Test
	void rejectsDuplicateRelationship() {
		assertInvalid(
				model(
						"Customer",
						List.of(
								entity("Customer", "customer", "customer_id", "id"),
								entity("Account", "account", "account_id", "id")),
						List.of(
								relationship("Customer", "Account", "ONE_TO_MANY", "customer_id", "customer_id"),
								relationship("Customer", "Account", "ONE_TO_ONE", "customer_id", "account_customer_id"))),
				"Duplicate relationship from 'Customer' to 'Account'");
	}

	@Test
	void rejectsCyclicGraph() {
		assertInvalid(
				model(
						"Customer",
						List.of(
								entity("Customer", "customer", "customer_id", "id"),
								entity("Account", "account", "account_id", "id"),
								entity("Subscription", "subscription", "subscription_id", "id")),
						List.of(
								relationship("Customer", "Account", "ONE_TO_MANY", "customer_id", "customer_id"),
								relationship("Account", "Subscription", "ONE_TO_MANY", "account_id", "account_id"),
								relationship("Subscription", "Customer", "MANY_TO_ONE", "customer_id", "customer_id"))),
				"Entity relationship graph contains a cycle: Customer -> Account -> Subscription -> Customer");
	}

	@Test
	void legacyConfigurationRemainsFlat() {
		assertThat(EntityRelationshipConfiguration.parseIfPresent(null)).isEmpty();
		assertThat(EntityRelationshipConfiguration.parseIfPresent(Map.of())).isEmpty();
		assertThat(EntityRelationshipConfiguration.parseIfPresent(Map.of("notes", "flat"))).isEmpty();
		EntityRelationshipConfiguration.validate(Map.of("notes", "flat"));
	}

	@Test
	void flatMappingEngineBehaviorIsUnchanged() {
		MigrationMappingRule rule = new MigrationMappingRule();
		rule.setEnabled(true);
		rule.setMandatory(true);
		rule.setSequenceNo(1);
		rule.setSourceField("customer_name");
		rule.setTargetField("name");
		rule.setTransformationType(TransformationType.TRIM);
		rule.setConfiguration(Map.of());

		var result = new MigrationRecordProcessor(new MappingEngine(new TransformationEngine()), new ValidationEngine())
				.process(new SourceRecord(1L, Map.of("customer_name", " Ada ")), List.of(rule));

		assertThat(result.status()).isEqualTo(MigrationRecordStatus.SUCCESS);
		assertThat(result.target().fields()).containsEntry("name", "Ada");
	}

	@ParameterizedTest
	@MethodSource("hierarchies")
	void configurationRoundTripsThroughJson(Hierarchy hierarchy) throws Exception {
		String json = OBJECT_MAPPER.writeValueAsString(hierarchy.configuration());
		Map<String, Object> deserialized = OBJECT_MAPPER.readValue(json, new TypeReference<>() {
		});

		EntityRelationshipMapping parsed = EntityRelationshipConfiguration.parse(deserialized);
		Map<String, Object> canonical = EntityRelationshipConfiguration.toConfiguration(parsed);
		EntityRelationshipMapping again = EntityRelationshipConfiguration.parse(
				OBJECT_MAPPER.readValue(OBJECT_MAPPER.writeValueAsString(canonical), new TypeReference<>() {
				}));

		assertThat(again).isEqualTo(parsed);
		assertThat(canonical).containsEntry("modelType", "ENTITY_RELATIONSHIP");
		assertThat(walk(again)).containsExactlyElementsOf(hierarchy.chain());
	}

	@Test
	void rejectsUnsupportedModelType() {
		assertInvalid(
				Map.of("modelType", "FLAT", "rootEntity", "Invoice"),
				"Unsupported modelType 'FLAT'. Supported value is ENTITY_RELATIONSHIP");
	}

	static Stream<Hierarchy> hierarchies() {
		return Stream.of(
				new Hierarchy(
						"customer-account-subscription",
						"Customer",
						List.of("Subscription", "Account", "Customer"),
						List.of("Customer", "Account", "Subscription"),
						model(
								"Customer",
								List.of(
										entity("Subscription", "subscription", "subscription_id", "id"),
										entity("Account", "account", "account_id", "id"),
										entity("Customer", "customer", "customer_id", "id")),
								List.of(
										relationship("Customer", "Account", "ONE_TO_MANY", "customer_id", "customer_id"),
										relationship("Account", "Subscription", "ONE_TO_MANY", "account_id", "account_id")))),
				new Hierarchy(
						"product-variant-price",
						"Product",
						List.of("Price", "Variant", "Product"),
						List.of("Product", "Variant", "Price"),
						model(
								"Product",
								List.of(
										entity("Price", "price", "price_id", "id"),
										entity("Variant", "variant", "variant_id", "id"),
										entity("Product", "product", "product_id", "id")),
								List.of(
										relationship("Product", "Variant", "ONE_TO_MANY", "product_id", "product_id"),
										relationship("Variant", "Price", "ONE_TO_MANY", "variant_id", "variant_id")))));
	}

	private static List<String> walk(EntityRelationshipMapping mapping) {
		List<String> visited = new ArrayList<>();
		String current = mapping.rootEntity();
		while (current != null && !visited.contains(current)) {
			visited.add(current);
			List<RelationshipDefinition> edges = mapping.graph().outgoing(current);
			current = edges.isEmpty() ? null : edges.get(0).child();
		}
		return visited;
	}

	private static void assertInvalid(Map<String, Object> configuration, String message) {
		assertThatThrownBy(() -> EntityRelationshipConfiguration.parse(configuration))
				.isInstanceOf(InvalidEntityRelationshipConfigurationException.class)
				.hasMessage(message);
	}

	private static Map<String, Object> model(
			String root,
			List<Map<String, Object>> entities,
			List<Map<String, Object>> relationships) {
		Map<String, Object> configuration = new LinkedHashMap<>();
		configuration.put("modelType", "ENTITY_RELATIONSHIP");
		configuration.put("rootEntity", root);
		configuration.put("entities", entities);
		if (relationships != null) {
			configuration.put("relationships", relationships);
		}
		return configuration;
	}

	private static Map<String, Object> entity(String name, String targetEntity, String sourceKey, String targetKey) {
		Map<String, Object> entity = new LinkedHashMap<>();
		entity.put("name", name);
		entity.put("targetEntity", targetEntity);
		entity.put("sourceKey", sourceKey);
		entity.put("targetKey", targetKey);
		return entity;
	}

	private static Map<String, Object> relationship(
			String parent,
			String child,
			String cardinality,
			String parentField,
			String childField) {
		Map<String, Object> key = new LinkedHashMap<>();
		key.put("parentField", parentField);
		key.put("childField", childField);
		Map<String, Object> relationship = new LinkedHashMap<>();
		relationship.put("parent", parent);
		relationship.put("child", child);
		relationship.put("cardinality", cardinality);
		relationship.put("keys", List.of(key));
		return relationship;
	}

	record Hierarchy(
			String label,
			String root,
			List<String> declaredOrder,
			List<String> chain,
			Map<String, Object> configuration
	) {
	}
}
