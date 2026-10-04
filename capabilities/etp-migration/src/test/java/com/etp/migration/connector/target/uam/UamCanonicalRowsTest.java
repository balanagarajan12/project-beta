package com.etp.migration.connector.target.uam;

import static org.assertj.core.api.Assertions.assertThat;

import com.etp.migration.connector.target.LoadableTargetRecord;
import com.etp.migration.processing.TargetRecord;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UamCanonicalRowsTest {

	@Test
	void foreignKeysUseTheConfiguredParentIdentity() {
		List<UamCanonicalRows.InsertGroup> groups = UamCanonicalRows.plan(configuration(), List.of(
				payload("AddressLocation", "LOC-000000001", Map.of("at_type", "geographicAddress", "name", "Address 001"), List.of()),
				payload("GeographicPointLocation", "GEO-LOC-000000001", Map.of("at_type", "geographicLocation"), List.of()),
				payload("GeographicLocation", "GEO-000000001", Map.of("geo_json_type", "Point"), List.of(
						parent("GeographicPointLocation", "GEO-LOC-000000001"))),
				payload("GeographicAddress", "ADDR-0000000001", Map.of("street_name", "Sample Street", "city", "Melbourne"), List.of(
						parent("AddressLocation", "LOC-000000001"),
						parent("GeographicLocation", "GEO-000000001"))),
				payload("LocationGeometry", "GEOM-000000001", Map.of("geometry_type", "Point", "x", "144.910000", "y", "-37.790000"), List.of(
						parent("GeographicPointLocation", "GEO-LOC-000000001"),
						parent("GeographicLocation", "GEO-000000001")))));

		assertThat(groups).extracting(UamCanonicalRows.InsertGroup::table)
				.containsExactly("location", "location", "geographic_location", "geographic_address", "location_geometry");
		assertThat(value(groups.get(0), "id")).isEqualTo("LOC-000000001");
		assertThat(value(groups.get(0), "at_type")).isEqualTo("geographicAddress");
		assertThat(value(groups.get(1), "id")).isEqualTo("GEO-LOC-000000001");
		assertThat(value(groups.get(2), "location_id")).isEqualTo("GEO-LOC-000000001");
		assertThat(value(groups.get(3), "location_id")).isEqualTo("LOC-000000001");
		assertThat(value(groups.get(3), "geographic_location_id")).isEqualTo("GEO-000000001");
		assertThat(value(groups.get(4), "x")).isEqualTo(new java.math.BigDecimal("144.910000"));
		assertThat(value(groups.get(4), "y")).isEqualTo(new java.math.BigDecimal("-37.790000"));
		assertThat(value(groups.get(4), "location_id")).isEqualTo("GEO-000000001");
		assertThat(value(groups.get(4), "location_id")).isNotEqualTo("GEO-LOC-000000001");
		assertThat(value(groups.get(0), "created_at")).isInstanceOf(OffsetDateTime.class);
		assertThat(value(groups.get(1), "created_at")).isEqualTo(value(groups.get(0), "created_at"));
		assertThat(groups.get(3).columns()).doesNotContain("created_at", "state");
	}

	@Test
	void mappedCreatedAtIsKept() {
		List<UamCanonicalRows.InsertGroup> groups = UamCanonicalRows.plan(configuration(), List.of(
				payload("AddressLocation", "LOC-000000001", Map.of(
						"at_type", "geographicAddress",
						"created_at", "2020-01-02T03:04:05Z"), List.of())));
		assertThat(value(groups.get(0), "created_at")).isEqualTo(OffsetDateTime.parse("2020-01-02T03:04:05Z"));
	}

	private static Object value(UamCanonicalRows.InsertGroup group, String column) {
		return group.parameters().get(0).get(group.columns().indexOf(column));
	}

	private static LoadableTargetRecord payload(
			String entity,
			String targetIdentity,
			Map<String, Object> attributes,
			List<Map<String, Object>> relationships) {
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("entity", entity);
		fields.put("targetKey", "id");
		fields.put("targetIdentity", targetIdentity);
		fields.put("attributes", attributes);
		fields.put("relationships", relationships);
		return new LoadableTargetRecord(1L, new TargetRecord(fields), "key-" + entity);
	}

	private static Map<String, Object> parent(String entity, String targetIdentity) {
		Map<String, Object> parent = new LinkedHashMap<>();
		parent.put("parentEntity", entity);
		parent.put("parentTargetIdentity", targetIdentity);
		return parent;
	}

	private static Map<String, Object> configuration() {
		Map<String, Object> tables = new LinkedHashMap<>();
		tables.put("AddressLocation", "location");
		tables.put("GeographicPointLocation", "location");
		tables.put("GeographicLocation", "geographic_location");
		tables.put("GeographicAddress", "geographic_address");
		tables.put("LocationGeometry", "location_geometry");
		return Map.of(
				"target", Map.of("mode", "UAM", "schema", "uam"),
				"tables", tables,
				"loadOrder", List.of(
						"AddressLocation",
						"GeographicPointLocation",
						"GeographicLocation",
						"GeographicAddress",
						"LocationGeometry"),
				"foreignKeys", List.of(
						Map.of("entity", "GeographicAddress", "parent", "AddressLocation", "column", "location_id"),
						Map.of("entity", "GeographicAddress", "parent", "GeographicLocation", "column", "geographic_location_id"),
						Map.of("entity", "GeographicLocation", "parent", "GeographicPointLocation", "column", "location_id"),
						Map.of("entity", "LocationGeometry", "parent", "GeographicLocation", "column", "location_id")),
				"loadTimestampColumns", List.of(Map.of("table", "location", "column", "created_at")),
				"columnTypes", List.of(
						Map.of("table", "location", "column", "created_at", "type", "timestamptz"),
						Map.of("table", "location_geometry", "column", "x", "type", "numeric"),
						Map.of("table", "location_geometry", "column", "y", "type", "numeric")));
	}
}
