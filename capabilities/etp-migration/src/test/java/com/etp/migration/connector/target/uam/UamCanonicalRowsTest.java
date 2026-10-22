package com.etp.migration.connector.target.uam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.etp.migration.connector.target.LoadableTargetRecord;
import com.etp.migration.connector.target.TargetInfrastructureException;
import com.etp.migration.processing.TargetRecord;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UamCanonicalRowsTest {

	@Test
	void foreignKeysUseTheParentColumnNamedByTheRelationship() {
		List<UamCanonicalRows.InsertGroup> groups = UamCanonicalRows.plan(configuration(), List.of(
				payload("AddressLocation", "location", "LOC-000000001", Map.of("at_type", "geographicAddress", "name", "Address 001"), List.of()),
				payload("GeographicPointLocation", "location", "GEO-LOC-000000001", Map.of("at_type", "geographicLocation"), List.of()),
				payload("GeographicLocation", "geographic_location", "GEO-000000001", Map.of("geo_json_type", "Point"), List.of(
						parent("GeographicPointLocation", "GEO-LOC-000000001", "location_id", "id"))),
				payload("GeographicAddress", "geographic_address", "ADDR-0000000001", Map.of("street_name", "Sample Street", "city", "Melbourne"), List.of(
						parent("AddressLocation", "LOC-000000001", "location_id", "id"),
						parent("GeographicLocation", "GEO-000000001", "geographic_location_id", "id"))),
				payload("LocationGeometry", "location_geometry", "GEOM-000000001", Map.of("geometry_type", "Point", "x", "144.910000", "y", "-37.790000"), List.of(
						parent("GeographicPointLocation", "GEO-LOC-000000001"),
						parent("GeographicLocation", "GEO-000000001", "location_id", "id")))));

		assertThat(groups).extracting(UamCanonicalRows.InsertGroup::table)
				.containsExactly("location", "location", "geographic_location", "geographic_address", "location_geometry");
		assertThat(value(groups.get(0), "id")).isEqualTo("LOC-000000001");
		assertThat(value(groups.get(0), "at_type")).isEqualTo("geographicAddress");
		assertThat(value(groups.get(1), "id")).isEqualTo("GEO-LOC-000000001");
		assertThat(value(groups.get(2), "location_id")).isEqualTo("GEO-LOC-000000001");
		assertThat(value(groups.get(3), "location_id")).isEqualTo("LOC-000000001");
		assertThat(value(groups.get(3), "geographic_location_id")).isEqualTo("GEO-000000001");
		assertThat(value(groups.get(4), "x")).isEqualTo("144.910000");
		assertThat(value(groups.get(4), "y")).isEqualTo("-37.790000");
		assertThat(value(groups.get(4), "location_id")).isEqualTo("GEO-000000001");
		assertThat(value(groups.get(4), "location_id")).isNotEqualTo("GEO-LOC-000000001");
		assertThat(groups.get(0).columns()).doesNotContain("created_at");
		assertThat(groups.get(3).columns()).doesNotContain("created_at", "state");
	}

	@Test
	void relationshipCopiesTheNamedParentColumnRatherThanTheParentPrimaryKey() {
		List<UamCanonicalRows.InsertGroup> groups = UamCanonicalRows.plan(configuration(), List.of(
				payload("Site", "site", "SITE-1", Map.of("name", "Independent"), List.of()),
				payload("GeographicSubAddress", "geographic_sub_address", "SUB-1", Map.of("sub_unit_number", "12"), List.of(
						parent("GeographicAddress", "ADDR-1", "address_id", "location_id"))),
				address("ADDR-1", "LOC-9")));

		assertThat(groups).extracting(UamCanonicalRows.InsertGroup::table)
				.containsExactly("geographic_address", "geographic_sub_address", "site");
		assertThat(value(groups.get(1), "address_id")).isEqualTo("LOC-9");
		assertThat(value(groups.get(1), "address_id")).isNotEqualTo("ADDR-1");
		assertThat(value(groups.get(0), "location_id")).isEqualTo("LOC-9");
		assertThat(value(groups.get(0), "id")).isEqualTo("ADDR-1");
	}

	@Test
	void legacyDefinitionFieldsAreNotUsed() {
		Map<String, Object> configuration = new LinkedHashMap<>();
		configuration.put("target", Map.of("mode", "UAM", "schema", "uam"));
		configuration.put("tables", Map.of("GeographicAddress", "ignored_table"));
		configuration.put("loadOrder", List.of("GeographicSubAddress", "GeographicAddress"));
		configuration.put("foreignKeys", List.of(Map.of(
				"entity", "GeographicSubAddress",
				"parent", "GeographicAddress",
				"column", "address_id")));
		configuration.put("loadTimestampColumns", List.of(Map.of("table", "location", "column", "created_at")));
		configuration.put("columnTypes", List.of(Map.of(
				"table", "location_geometry", "column", "x", "type", "numeric")));

		List<UamCanonicalRows.InsertGroup> groups = UamCanonicalRows.plan(configuration, List.of(
				payload("GeographicSubAddress", "geographic_sub_address", "SUB-1", Map.of(), List.of(
						parent("GeographicAddress", "ADDR-1", "address_id", "location_id"))),
				address("ADDR-1", "LOC-9")));

		assertThat(groups).extracting(UamCanonicalRows.InsertGroup::table)
				.containsExactly("geographic_address", "geographic_sub_address");
		assertThat(value(groups.get(1), "address_id")).isEqualTo("LOC-9");
		assertThat(groups.get(0).columns()).doesNotContain("created_at");
	}

	@Test
	void cyclicForeignKeysFailWithTheCycle() {
		assertThatThrownBy(() -> UamCanonicalRows.plan(configuration(), List.of(
				payload("Left", "left_table", "L-1", Map.of(), List.of(
						parent("Right", "R-1", "right_id", "id"))),
				payload("Right", "right_table", "R-1", Map.of(), List.of(
						parent("Left", "L-1", "left_id", "id"))))))
				.isInstanceOf(TargetInfrastructureException.class)
				.hasMessageContaining("Entity relationship graph contains a cycle");
	}

	@Test
	void addressLocationIdentityResolvesWhenThatParentRowIsInAnotherBatch() {
		List<UamCanonicalRows.InsertGroup> groups = UamCanonicalRows.plan(configuration(), List.of(
				payload("AddressLocation", "location", "LOC-000000002", Map.of("at_type", "geographicAddress"), List.of()),
				payload("GeographicAddress", "geographic_address", "ADDR-0000000002", Map.of("street_name", "Next"), List.of(
						parent("AddressLocation", "LOC-000000001", "location_id", "id")))));

		assertThat(groups).extracting(UamCanonicalRows.InsertGroup::table)
				.containsExactly("location", "geographic_address");
		assertThat(value(groups.get(1), "location_id")).isEqualTo("LOC-000000001");
		assertThat(value(groups.get(1), "id")).isEqualTo("ADDR-0000000002");
	}

	@Test
	void parentTargetKeyResolvesTheIdentityWhenNoParentRowIsInTheBatch() {
		Map<String, Object> link = parent("AddressLocation", "LOC-000000001", "location_id", "id");
		link.put("parentTargetKey", "id");
		link.put("parentTargetEntity", "location");
		List<UamCanonicalRows.InsertGroup> groups = UamCanonicalRows.plan(configuration(), List.of(
				payload("GeographicAddress", "geographic_address", "ADDR-0000000001", Map.of(), List.of(link))));

		assertThat(groups).singleElement().satisfies(group -> {
			assertThat(group.table()).isEqualTo("geographic_address");
			assertThat(value(group, "location_id")).isEqualTo("LOC-000000001");
		});
		assertThat(UamCanonicalRows.planDetailed(configuration(), List.of(
				payload("GeographicAddress", "geographic_address", "ADDR-0000000001", Map.of(), List.of(link))))
				.foreignKeys())
				.singleElement()
				.satisfies(binding -> {
					assertThat(binding.parentTable()).isEqualTo("location");
					assertThat(binding.referencesColumn()).isEqualTo("id");
				});
	}

	@Test
	void referencedColumnThatIsNotTheParentIdentityStillRequiresTheParentRow() {
		Map<String, Object> link = parent("GeographicAddress", "ADDR-0000000001", "address_id", "location_id");
		link.put("parentTargetKey", "id");
		assertThatThrownBy(() -> UamCanonicalRows.plan(configuration(), List.of(
				payload("GeographicSubAddress", "geographic_sub_address", "SUB-000000001", Map.of(), List.of(link)))))
				.isInstanceOf(TargetInfrastructureException.class)
				.hasMessageContaining("was not resolved");
	}

	@Test
	void unresolvedParentFailsBeforeInsertPlanningCompletes() {
		assertThatThrownBy(() -> UamCanonicalRows.plan(configuration(), List.of(
				payload("GeographicAddress", "geographic_address", "ADDR-1", Map.of(), List.of(
						parent("AddressLocation", "LOC-1", "location_id", "id"))))))
				.isInstanceOf(TargetInfrastructureException.class)
				.hasMessageContaining("was not resolved");
	}

	@Test
	void postgresqlModeUsesTheConfiguredSchema() {
		Map<String, Object> configuration = new LinkedHashMap<>();
		configuration.put("target", Map.of("mode", "POSTGRESQL", "schema", "customer"));
		List<UamCanonicalRows.InsertGroup> groups = UamCanonicalRows.plan(configuration, List.of(
				payload("Customer", "customer", "CUST-1", Map.of("name", "Ada"), List.of())));
		assertThat(groups).hasSize(1);
		assertThat(groups.get(0).schema()).isEqualTo("customer");
		assertThat(groups.get(0).table()).isEqualTo("customer");
	}

	@Test
	void nullAttributeValuesStayInTheSameInsertGroup() {
		Map<String, Object> withNulls = subAddressAttributes("Tower", "12", null, null, null, null);
		Map<String, Object> populated = subAddressAttributes("Annex", "2", "Floor", "3", "Unit", "West");

		List<UamCanonicalRows.InsertGroup> groups = UamCanonicalRows.plan(configuration(), List.of(
				address("ADDR-1", "LOC-9"),
				payload("GeographicSubAddress", "geographic_sub_address", "SUB-1", withNulls, List.of(
						parent("GeographicAddress", "ADDR-1", "address_id", "id"))),
				payload("GeographicSubAddress", "geographic_sub_address", "SUB-2", populated, List.of(
						parent("GeographicAddress", "ADDR-1", "address_id", "id")))));

		assertThat(groups).extracting(UamCanonicalRows.InsertGroup::table)
				.containsExactly("geographic_address", "geographic_sub_address");
		UamCanonicalRows.InsertGroup subAddresses = groups.get(1);
		assertThat(subAddresses.parameters()).hasSize(2);
		assertThat(subAddresses.columns()).contains(
				"id", "building_name", "level_type", "level_number", "sub_unit_type", "sub_unit_name", "address_id");
		assertThat(subAddresses.columns()).doesNotContain("created_at");
		assertThat(value(subAddresses, 0, "id")).isEqualTo("SUB-1");
		assertThat(value(subAddresses, 0, "building_name")).isEqualTo("Tower");
		assertThat(value(subAddresses, 0, "sub_unit_number")).isEqualTo("12");
		assertThat(value(subAddresses, 0, "level_type")).isNull();
		assertThat(value(subAddresses, 0, "level_number")).isNull();
		assertThat(value(subAddresses, 0, "sub_unit_type")).isNull();
		assertThat(value(subAddresses, 0, "sub_unit_name")).isNull();
		assertThat(value(subAddresses, 0, "address_id")).isEqualTo("ADDR-1");
		assertThat(value(subAddresses, 1, "id")).isEqualTo("SUB-2");
		assertThat(value(subAddresses, 1, "level_type")).isEqualTo("Floor");
		assertThat(value(subAddresses, 1, "address_id")).isEqualTo("ADDR-1");
		assertThat(value(groups.get(0), "id")).isEqualTo("ADDR-1");
		assertThat(value(groups.get(0), "location_id")).isEqualTo("LOC-9");
	}

	@Test
	void nullableNullAttributeBindsAsSqlNull() {
		List<UamCanonicalRows.InsertGroup> groups = UamCanonicalRows.plan(configuration(), List.of(
				payload("GeographicSubAddress", "geographic_sub_address", "SUB-1",
						subAddressAttributes("Tower", "12", null, null, null, null), List.of())));

		List<UamCanonicalRows.InsertGroup> bound = UamTargetColumns.bind(
				groups.get(0),
				catalog(groups.get(0), Set.of("id")));

		assertThat(bound).hasSize(1);
		assertThat(value(bound.get(0), "id")).isEqualTo("SUB-1");
		assertThat(value(bound.get(0), "building_name")).isEqualTo("Tower");
		assertThat(value(bound.get(0), "level_type")).isNull();
		assertThat(value(bound.get(0), "level_number")).isNull();
		assertThat(value(bound.get(0), "sub_unit_type")).isNull();
		assertThat(value(bound.get(0), "sub_unit_name")).isNull();
		assertThat(bound.get(0).columns()).contains("level_type", "level_number", "sub_unit_type", "sub_unit_name");
	}

	@Test
	void nullOnARequiredColumnFailsAtCatalogBinding() {
		Map<String, Object> attributes = new LinkedHashMap<>();
		attributes.put("at_type", null);
		List<UamCanonicalRows.InsertGroup> groups = UamCanonicalRows.plan(configuration(), List.of(
				payload("AddressLocation", "location", "LOC-1", attributes, List.of())));

		assertThat(value(groups.get(0), "at_type")).isNull();
		assertThatThrownBy(() -> UamTargetColumns.bind(groups.get(0), catalog(groups.get(0), Set.of("id", "at_type"))))
				.isInstanceOf(TargetInfrastructureException.class)
				.hasMessage("Column 'at_type' on 'location' is required");
	}

	@Test
	void mappedCreatedAtIsKeptAsTheSourceValueUntilCatalogConversion() {
		List<UamCanonicalRows.InsertGroup> groups = UamCanonicalRows.plan(configuration(), List.of(
				payload("AddressLocation", "location", "LOC-000000001", Map.of(
						"at_type", "geographicAddress",
						"created_at", "2020-01-02T03:04:05Z"), List.of())));
		assertThat(value(groups.get(0), "created_at")).isEqualTo("2020-01-02T03:04:05Z");
	}

	private static Object value(UamCanonicalRows.InsertGroup group, String column) {
		return value(group, 0, column);
	}

	private static Object value(UamCanonicalRows.InsertGroup group, int row, String column) {
		return group.parameters().get(row).get(group.columns().indexOf(column));
	}

	private static Map<String, Object> subAddressAttributes(
			String buildingName,
			String subUnitNumber,
			String levelType,
			String levelNumber,
			String subUnitType,
			String subUnitName) {
		Map<String, Object> attributes = new LinkedHashMap<>();
		attributes.put("building_name", buildingName);
		attributes.put("sub_unit_number", subUnitNumber);
		attributes.put("level_type", levelType);
		attributes.put("level_number", levelNumber);
		attributes.put("sub_unit_type", subUnitType);
		attributes.put("sub_unit_name", subUnitName);
		return attributes;
	}

	private static Map<String, UamTargetColumns.Column> catalog(
			UamCanonicalRows.InsertGroup group,
			Set<String> required) {
		Map<String, UamTargetColumns.Column> columns = new LinkedHashMap<>();
		for (String name : group.columns()) {
			columns.put(name, new UamTargetColumns.Column(
					group.schema(),
					group.table(),
					name,
					"varchar",
					!required.contains(name),
					null,
					false,
					null,
					null));
		}
		return columns;
	}

	private static LoadableTargetRecord payload(
			String entity,
			String targetEntity,
			String targetIdentity,
			Map<String, Object> attributes,
			List<Map<String, Object>> relationships) {
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("entity", entity);
		fields.put("targetEntity", targetEntity);
		fields.put("targetKey", "id");
		fields.put("targetIdentity", targetIdentity);
		fields.put("attributes", attributes);
		fields.put("relationships", relationships);
		return new LoadableTargetRecord(1L, new TargetRecord(fields), "key-" + entity);
	}

	private static LoadableTargetRecord address(String targetIdentity, String locationId) {
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("entity", "GeographicAddress");
		fields.put("targetEntity", "geographic_address");
		fields.put("targetKey", "id");
		fields.put("targetIdentity", targetIdentity);
		fields.put("attributes", Map.of("location_id", locationId));
		fields.put("relationships", List.of());
		return new LoadableTargetRecord(1L, new TargetRecord(fields), "key-address");
	}

	private static Map<String, Object> parent(String entity, String targetIdentity) {
		Map<String, Object> parent = new LinkedHashMap<>();
		parent.put("parentEntity", entity);
		parent.put("parentTargetIdentity", targetIdentity);
		return parent;
	}

	private static Map<String, Object> parent(
			String entity,
			String targetIdentity,
			String column,
			String referencesColumn) {
		Map<String, Object> parent = parent(entity, targetIdentity);
		parent.put("targetForeignKey", Map.of("column", column, "referencesColumn", referencesColumn));
		return parent;
	}

	private static Map<String, Object> configuration() {
		return Map.of("target", Map.of("mode", "UAM", "schema", "uam"));
	}
}
