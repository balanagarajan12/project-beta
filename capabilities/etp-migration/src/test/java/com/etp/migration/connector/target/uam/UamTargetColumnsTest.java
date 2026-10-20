package com.etp.migration.connector.target.uam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.etp.migration.connector.target.TargetInfrastructureException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;

class UamTargetColumnsTest {

	@Test
	void catalogTypesConvertValuesAndOmitGeneratedOrDefaultedColumns() throws Exception {
		Map<String, UamTargetColumns.Column> catalog = new LinkedHashMap<>();
		catalog.put("id", column("id", "varchar", true, null, false, null));
		catalog.put("x", column("x", "numeric", true, null, false, null));
		catalog.put("created_at", column("created_at", "timestamptz", false, null, false, null));
		catalog.put("active", column("active", "bool", true, null, false, null));
		catalog.put("count", column("count", "int4", true, null, false, null));
		catalog.put("external_id", column("external_id", "uuid", true, null, false, null));
		catalog.put("payload", column("payload", "jsonb", true, null, false, null));
		catalog.put("status", column("status", "varchar", false, "'PLANNED'", false, null));
		catalog.put("search_text", column("search_text", "text", true, null, false, "ALWAYS"));

		UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
		List<Object> row = new ArrayList<>();
		row.add("LOC-1");
		row.add("144.910000");
		row.add("2020-01-02T03:04:05Z");
		row.add("true");
		row.add("3");
		row.add(id.toString());
		row.add("{\"type\":\"Point\"}");
		row.add(null);
		row.add("ignored");
		UamCanonicalRows.InsertGroup bound = UamTargetColumns.bind(group(List.of(
				"id", "x", "created_at", "active", "count", "external_id", "payload", "status", "search_text"),
				row),
				catalog).get(0);

		assertThat(bound.columns())
				.containsExactly("id", "x", "created_at", "active", "count", "external_id", "payload");
		assertThat(bound.parameters().get(0).get(1)).isEqualTo(new BigDecimal("144.910000"));
		assertThat(bound.parameters().get(0).get(2)).isEqualTo(OffsetDateTime.parse("2020-01-02T03:04:05Z"));
		assertThat(bound.parameters().get(0).get(3)).isEqualTo(true);
		assertThat(bound.parameters().get(0).get(4)).isEqualTo(3);
		assertThat(bound.parameters().get(0).get(5)).isEqualTo(id);
		assertThat(bound.parameters().get(0).get(6)).isInstanceOf(PGobject.class);
		assertThat(((PGobject) bound.parameters().get(0).get(6)).getType()).isEqualTo("jsonb");
	}

	@Test
	void timestampWithoutZoneAndDateUseTheCatalogType() {
		UamTargetColumns.Column timestamp = column("updated_at", "timestamp", true, null, false, null);
		UamTargetColumns.Column date = column("observed_on", "date", true, null, false, null);

		assertThat(UamTargetColumns.coerce(timestamp, "2020-01-02T03:04:05"))
				.isEqualTo(LocalDateTime.parse("2020-01-02T03:04:05"));
		assertThat(UamTargetColumns.coerce(date, "2020-01-02")).isEqualTo(LocalDate.parse("2020-01-02"));
	}

	@Test
	void requiredColumnRejectsABlankValue() {
		Map<String, UamTargetColumns.Column> catalog = Map.of(
				"created_at", column("created_at", "timestamptz", false, null, false, null));

		assertThatThrownBy(() -> UamTargetColumns.bind(
				group(List.of("created_at"), List.of(" ")),
				catalog))
				.isInstanceOf(TargetInfrastructureException.class)
				.hasMessage("Column 'created_at' on 'location' is required");
	}

	@Test
	void invalidNumericNamesTheColumn() {
		UamTargetColumns.Column column = column("x", "numeric", true, null, false, null);

		assertThatThrownBy(() -> UamTargetColumns.coerce(column, "north"))
				.isInstanceOf(TargetInfrastructureException.class)
				.hasMessage("Value for column 'x' on 'location' is not a valid numeric");
	}

	@Test
	void blankValuesBecomeNullAndDefaultedCellsAreOmittedPerRow() throws Exception {
		Map<String, UamTargetColumns.Column> catalog = new LinkedHashMap<>();
		catalog.put("id", column("id", "varchar", false, null, false, null));
		catalog.put("note", column("note", "text", true, null, false, null));
		catalog.put("status", column("status", "varchar", false, "'PLANNED'", false, null));

		List<Object> blankDefault = new ArrayList<>();
		blankDefault.add("LOC-1");
		blankDefault.add("   ");
		blankDefault.add("");
		List<Object> explicitStatus = new ArrayList<>();
		explicitStatus.add("LOC-2");
		explicitStatus.add(null);
		explicitStatus.add("ACTIVE");
		List<UamCanonicalRows.InsertGroup> bound = UamTargetColumns.bind(
				new UamCanonicalRows.InsertGroup(
						"uam",
						"location",
						List.of("id", "note", "status"),
						List.of(blankDefault, explicitStatus)),
				catalog);

		assertThat(bound).hasSize(2);
		assertThat(bound.get(0).columns()).containsExactly("id", "note");
		assertThat(bound.get(0).parameters().get(0)).containsExactly("LOC-1", null);
		assertThat(bound.get(1).columns()).containsExactly("id", "note", "status");
		assertThat(bound.get(1).parameters().get(0)).containsExactly("LOC-2", null, "ACTIVE");
	}

	@Test
	void identityAndSerialColumnsKeepSuppliedValuesAndOmitBlanks() {
		Map<String, UamTargetColumns.Column> catalog = new LinkedHashMap<>();
		catalog.put("id", column("id", "varchar", false, null, false, null));
		catalog.put("seq_id", identity("seq_id", "BY DEFAULT"));
		catalog.put("legacy_seq", column("legacy_seq", "int4", false, "nextval('location_legacy_seq_seq'::regclass)", false, null));

		List<UamCanonicalRows.InsertGroup> bound = UamTargetColumns.bind(
				new UamCanonicalRows.InsertGroup(
						"uam",
						"location",
						List.of("id", "seq_id", "legacy_seq"),
						List.of(row("LOC-1", " ", null), row("LOC-2", "40", "8"))),
				catalog);

		assertThat(bound).hasSize(2);
		assertThat(bound.get(0).columns()).containsExactly("id");
		assertThat(bound.get(1).columns()).containsExactly("id", "seq_id", "legacy_seq");
		assertThat(bound.get(1).parameters().get(0)).containsExactly("LOC-2", 40, 8);
	}

	@Test
	void identityAlwaysRejectsASuppliedValue() {
		Map<String, UamTargetColumns.Column> catalog = new LinkedHashMap<>();
		catalog.put("id", column("id", "varchar", false, null, false, null));
		catalog.put("always_id", identity("always_id", "ALWAYS"));

		assertThatThrownBy(() -> UamTargetColumns.bind(
				group(List.of("id", "always_id"), row("LOC-1", "5")),
				catalog))
				.isInstanceOf(TargetInfrastructureException.class)
				.hasMessage("Column 'always_id' on 'location' is generated by the database and cannot accept a value");
	}

	@Test
	void omittingTheInsertKeyIsRejected() {
		Map<String, UamTargetColumns.Column> catalog = Map.of(
				"id", identity("id", "BY DEFAULT"),
				"name", column("name", "varchar", true, null, false, null));

		assertThatThrownBy(() -> UamTargetColumns.bind(
				group(List.of("id", "name"), row(" ", "Main")),
				catalog))
				.isInstanceOf(TargetInfrastructureException.class)
				.hasMessage("Column 'id' on 'location' is assigned by the database and cannot be omitted from the insert");
	}

	@Test
	void catalogAliasesConvertNumericTemporalAndJsonValues() throws Exception {
		assertThat(UamTargetColumns.coerce(column("whole", "int8", true, null, false, null), "9000000000"))
				.isEqualTo(9_000_000_000L);
		assertThat(UamTargetColumns.coerce(column("ratio", "float8", true, null, false, null), "1.5"))
				.isEqualTo(1.5d);
		assertThat(UamTargetColumns.coerce(column("code", "bpchar", true, null, false, null), "AUS"))
				.isEqualTo("AUS");
		assertThat(UamTargetColumns.coerce(column("observed_on", "date", true, null, false, null), "2020-01-02"))
				.isEqualTo(LocalDate.parse("2020-01-02"));
		assertThat(UamTargetColumns.coerce(column("starts_at", "time", true, null, false, null), "03:04:05"))
				.isEqualTo(LocalTime.parse("03:04:05"));
		assertThat(UamTargetColumns.coerce(column("starts_at", "timetz", true, null, false, null), "03:04:05Z"))
				.isEqualTo(OffsetTime.parse("03:04:05Z"));
		OffsetDateTime source = OffsetDateTime.parse("2020-01-02T03:04:05Z");
		assertThat(UamTargetColumns.coerce(column("created_at", "timestamptz", false, null, false, null), source))
				.isSameAs(source);
		assertThat(UamTargetColumns.coerce(column("active", "bool", true, null, false, null), "no"))
				.isEqualTo(false);

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("type", "Point");
		PGobject json = (PGobject) UamTargetColumns.coerce(
				column("payload", "json", true, null, false, null),
				payload);
		assertThat(json.getType()).isEqualTo("json");
		assertThat(json.getValue()).isEqualTo("{\"type\":\"Point\"}");
	}

	private static UamCanonicalRows.InsertGroup group(List<String> columns, List<Object> row) {
		return new UamCanonicalRows.InsertGroup("uam", "location", columns, List.of(row));
	}

	private static List<Object> row(Object... values) {
		List<Object> row = new ArrayList<>();
		java.util.Collections.addAll(row, values);
		return row;
	}

	private static UamTargetColumns.Column identity(String name, String generation) {
		return new UamTargetColumns.Column(
				"uam", "location", name, "int4", false, null, true, generation, "NEVER");
	}

	private static UamTargetColumns.Column column(
			String name,
			String udt,
			boolean nullable,
			String defaultExpression,
			boolean identity,
			String generated) {
		return new UamTargetColumns.Column(
				"uam", "location", name, udt, nullable, defaultExpression, identity, null, generated);
	}
}
