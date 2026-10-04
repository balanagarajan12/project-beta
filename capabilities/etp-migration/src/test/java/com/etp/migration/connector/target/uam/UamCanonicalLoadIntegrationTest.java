package com.etp.migration.connector.target.uam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.etp.migration.connector.target.LoadableTargetRecord;
import com.etp.migration.connector.target.MigrationTargetContext;
import com.etp.migration.connector.target.TargetInfrastructureException;
import com.etp.migration.connector.target.postgresql.PostgreSqlStagedEntityReader;
import com.etp.migration.connector.target.postgresql.PostgreSqlTargetClientConfig;
import com.etp.migration.connector.target.postgresql.PostgreSqlTargetConnector;
import com.etp.migration.entity.LoadMode;
import com.etp.migration.entity.MigrationConfigStatus;
import com.etp.migration.entity.MigrationDefinition;
import com.etp.migration.processing.TargetRecord;
import com.etp.migration.repository.MigrationDefinitionRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
class UamCanonicalLoadIntegrationTest {

	@TempDir
	static Path storageRoot;

	@DynamicPropertySource
	static void storageProperties(DynamicPropertyRegistry registry) {
		registry.add("etp.migration.storage.root", () -> storageRoot.toAbsolutePath().toString());
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private MigrationDefinitionRepository migrationDefinitionRepository;

	@Autowired
	private TransactionTemplate transactionTemplate;

	@Autowired
	private UamPostgreSqlTargetConnector canonicalConnector;

	@Autowired
	private PostgreSqlTargetConnector stagingConnector;

	@Autowired
	private PostgreSqlStagedEntityReader stagedEntityReader;

	@Autowired
	@Qualifier(PostgreSqlTargetClientConfig.DATASOURCE_BEAN)
	private DataSource targetDataSource;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	@BeforeEach
	void createCanonicalTables() throws Exception {
		execute("DROP SCHEMA IF EXISTS uam CASCADE");
		execute("CREATE SCHEMA uam");
		execute("""
				CREATE TABLE uam.location (
				  id varchar(64) PRIMARY KEY,
				  href varchar(255),
				  name varchar(255),
				  description text,
				  status varchar(32) DEFAULT 'PLANNED',
				  version varchar(16) DEFAULT '1.0',
				  at_type varchar(64) NOT NULL,
				  at_base_type varchar(64),
				  at_schema_location varchar(255),
				  valid_for_start_date timestamptz,
				  valid_for_end_date timestamptz,
				  created_at timestamptz NOT NULL,
				  updated_at timestamptz
				)
				""");
		execute("""
				CREATE TABLE uam.geographic_location (
				  id varchar(64) PRIMARY KEY,
				  location_id varchar(64) NOT NULL UNIQUE REFERENCES uam.location (id),
				  spatial_ref_sys varchar(128) NOT NULL DEFAULT 'urn:ogc:def:crs:EPSG::4326',
				  accuracy varchar(64),
				  geo_json_type varchar(64) NOT NULL
				)
				""");
		execute("""
				CREATE TABLE uam.geographic_address (
				  id varchar(64) PRIMARY KEY,
				  location_id varchar(64) NOT NULL UNIQUE REFERENCES uam.location (id),
				  geographic_location_id varchar(64) REFERENCES uam.geographic_location (id),
				  validation_status varchar(32) NOT NULL DEFAULT 'DRAFT',
				  address_type varchar(64) DEFAULT 'CivicAddress',
				  street_number varchar(32),
				  street_number_last varchar(32),
				  street_number_suffix varchar(16),
				  street_name varchar(128),
				  street_type varchar(32),
				  street_suffix varchar(32),
				  postcode varchar(32),
				  postcode_extension varchar(16),
				  locality varchar(128),
				  city varchar(128),
				  state_or_province varchar(128),
				  country varchar(64),
				  country_code varchar(8)
				)
				""");
		execute("""
				CREATE TABLE uam.location_geometry (
				  id varchar(64) PRIMARY KEY,
				  location_id varchar(64) NOT NULL UNIQUE REFERENCES uam.geographic_location (id),
				  geometry_type varchar(32) NOT NULL,
				  x numeric,
				  y numeric,
				  z numeric,
				  raw_geojson jsonb
				)
				""");
		execute("""
				CREATE TABLE uam.geographic_sub_address (
				  id varchar(64) PRIMARY KEY,
				  address_id varchar(64) NOT NULL REFERENCES uam.geographic_address (id),
				  geographic_location_id varchar(64) REFERENCES uam.geographic_location (id),
				  private_street_name varchar(128) NOT NULL,
				  private_street_number varchar(32) NOT NULL,
				  sub_unit_type varchar(64),
				  sub_unit_number varchar(32),
				  sub_unit_name varchar(128),
				  level_type varchar(64),
				  level_number varchar(32),
				  building_name varchar(128)
				)
				""");
		execute("CREATE SCHEMA IF NOT EXISTS migration_staging");
		execute("""
				CREATE TABLE IF NOT EXISTS migration_staging.uam_entity_stage (
				  migration_run_id UUID NOT NULL,
				  migration_batch_id BIGINT NOT NULL,
				  source_record_number BIGINT NOT NULL,
				  target_payload JSONB NOT NULL,
				  idempotency_key VARCHAR(500),
				  loaded_at TIMESTAMPTZ NOT NULL
				)
				""");
		execute("""
				CREATE UNIQUE INDEX IF NOT EXISTS uk_uam_entity_stage_run_identity
				ON migration_staging.uam_entity_stage (migration_run_id, idempotency_key)
				""");
		execute("DELETE FROM target_identity_allocator");
		execute("DELETE FROM migration_identity_allocation");
	}

	@Test
	void oneAddressIsStagedWithoutTargetIdentities() throws Exception {
		String objectKey = "manual/one-" + System.nanoTime() + ".csv";
		write(objectKey, header() + "ADDR001,GEO001,Address 001,1,Sample Street,St,Melbourne,VIC,3001,Australia,AU,-37.790000,144.910000\n");
		UUID runId = migrate(objectKey, 5);

		assertThat(count("uam.location")).isZero();
		assertThat(count("uam.geographic_address")).isZero();
		assertThat(count("uam.geographic_sub_address")).isZero();
		assertThat(text("SELECT COUNT(*) FROM migration_identity_allocation WHERE migration_run_id = ?", runId))
				.isEqualTo("0");
		List<Map<String, Object>> staged = stagedPayloads(runId);
		assertThat(staged).hasSize(5);
		assertThat(staged).allSatisfy(payload -> assertThat(payload.get("targetIdentity")).isNull());
		Map<String, Object> address = stagedEntity(staged, "GeographicAddress", "ADDR001");
		assertThat(address.get("targetEntity")).isEqualTo("geographic_address");
		assertThat(address.get("sourceKey")).isEqualTo("address_id");
		assertThat(attributes(address).get("state_or_province")).isEqualTo("VIC");
		assertThat(relationships(address)).anySatisfy(link -> {
			assertThat(link.get("parentEntity")).isEqualTo("AddressLocation");
			assertThat(link.get("parentSourceIdentity")).isEqualTo("ADDR001");
			assertThat(link.get("parentTargetIdentity")).isNull();
		});
		assertThat(relationships(address)).anySatisfy(link -> {
			assertThat(link.get("parentEntity")).isEqualTo("GeographicLocation");
			assertThat(link.get("parentSourceIdentity")).isEqualTo("GEO001");
			assertThat(link.get("parentTargetIdentity")).isNull();
		});
		Map<String, Object> geometry = stagedEntity(staged, "LocationGeometry", "GEO001");
		assertThat(relationships(geometry)).anySatisfy(link -> {
			assertThat(link.get("parentEntity")).isEqualTo("GeographicLocation");
			assertThat(link.get("parentSourceIdentity")).isEqualTo("GEO001");
			assertThat(link.get("parentTargetIdentity")).isNull();
		});
	}

	@Test
	void oneHundredAddressesStageFiveHundredEntitiesWithoutTargetIdentities() throws Exception {
		String objectKey = "manual/hundred-" + System.nanoTime() + ".csv";
		write(objectKey, csv(100));
		UUID runId = migrate(objectKey, 500);

		assertThat(count("uam.location")).isZero();
		assertThat(count("uam.geographic_address")).isZero();
		assertThat(count("uam.geographic_location")).isZero();
		assertThat(count("uam.location_geometry")).isZero();
		assertThat(count("uam.geographic_sub_address")).isZero();
		assertThat(text("SELECT COUNT(*) FROM migration_identity_allocation WHERE migration_run_id = ?", runId))
				.isEqualTo("0");
		assertThat(text(
				"SELECT COUNT(*) FROM migration_staging.uam_entity_stage WHERE migration_run_id = ?",
				runId)).isEqualTo("500");
		long batchId = Long.parseLong(text("SELECT id FROM migration_batch WHERE migration_run_id = ?", runId));
		assertThat(stagedEntityReader.read(runId, batchId, Map.of("target", Map.of(
				"schema", "migration_staging",
				"stagingTable", "uam_entity_stage",
				"mergeStrategy", "NONE")))).hasSize(500);
		List<Map<String, Object>> staged = stagedPayloads(runId);
		Map<String, Object> address = stagedEntity(staged, "GeographicAddress", "ADDR100");
		assertThat(address.get("targetIdentity")).isNull();
		assertThat(relationships(address)).anySatisfy(link -> {
			assertThat(link.get("parentSourceIdentity")).isEqualTo("ADDR100");
			assertThat(link.get("parentTargetIdentity")).isNull();
		});
	}

	@Test
	void oneHundredAddressesPromoteIntoCanonicalTables() throws Exception {
		String objectKey = "manual/promote-hundred-" + System.nanoTime() + ".csv";
		write(objectKey, csv(100));
		UUID runId = executeMigration(objectKey, 500);

		assertThat(count("uam.location")).isZero();
		assertThat(count("uam.geographic_address")).isZero();
		assertThat(count("uam.geographic_location")).isZero();
		assertThat(count("uam.location_geometry")).isZero();
		assertThat(count("uam.geographic_sub_address")).isZero();
		assertThat(allocationCount(runId)).isEqualTo("0");
		assertThat(text(
				"SELECT COUNT(*) FROM migration_staging.uam_entity_stage WHERE migration_run_id = ?",
				runId)).isEqualTo("500");

		MvcResult reconciled = mockMvc.perform(post("/api/v1/migration-runs/{runId}/reconcile", runId).with(jwt()))
				.andExpect(status().isOk())
				.andReturn();
		String reconciliationBody = reconciled.getResponse().getContentAsString();
		assertThat(com.jayway.jsonpath.JsonPath.read(reconciliationBody, "$.status").toString())
				.as(reconciliationBody)
				.isEqualTo("PASSED_WITH_WARNINGS");
		assertThat(com.jayway.jsonpath.JsonPath.read(
				reconciliationBody, "$.reconciliation.full.details.entityReconciliation.entityStatus").toString())
				.isEqualTo("PASSED");
		assertThat(allocationCount(runId)).isEqualTo("0");
		assertThat(count("uam.location")).isZero();

		MvcResult prepared = mockMvc.perform(post("/api/v1/migration-runs/{runId}/promotion/prepare", runId).with(jwt()))
				.andExpect(status().isOk())
				.andReturn();
		String preparedBody = prepared.getResponse().getContentAsString();
		assertThat(com.jayway.jsonpath.JsonPath.read(preparedBody, "$.status").toString()).isEqualTo("READY");
		assertThat(((Number) com.jayway.jsonpath.JsonPath.read(preparedBody, "$.eligibleRecords")).intValue())
				.isEqualTo(500);

		MvcResult promoted = mockMvc.perform(post("/api/v1/migration-runs/{runId}/promote", runId).with(jwt()))
				.andExpect(status().isOk())
				.andReturn();
		String promotedBody = promoted.getResponse().getContentAsString();
		assertThat(com.jayway.jsonpath.JsonPath.read(promotedBody, "$.status").toString())
				.as(promotedBody)
				.isEqualTo("PROMOTED");

		assertThat(count("uam.location")).isEqualTo(200);
		assertThat(count("uam.geographic_address")).isEqualTo(100);
		assertThat(count("uam.geographic_location")).isEqualTo(100);
		assertThat(count("uam.location_geometry")).isEqualTo(100);
		assertThat(count("uam.geographic_sub_address")).isZero();
		assertThat(countWhere("uam.location", "id ~ '^LOC-[0-9]{9}$'")).isEqualTo(100);
		assertThat(countWhere("uam.location", "id ~ '^GEO-LOC-[0-9]{9}$'")).isEqualTo(100);
		assertThat(countWhere("uam.geographic_address", "id ~ '^ADDR-[0-9]{10}$'")).isEqualTo(100);
		assertThat(countWhere("uam.geographic_location", "id ~ '^GEO-[0-9]{9}$'")).isEqualTo(100);
		assertThat(countWhere("uam.location_geometry", "id ~ '^GEOM-[0-9]{9}$'")).isEqualTo(100);
		assertThat(countWhere("uam.geographic_address", "location_id ~ '^LOC-[0-9]{9}$'")).isEqualTo(100);
		assertThat(countWhere("uam.geographic_address", "geographic_location_id ~ '^GEO-[0-9]{9}$'")).isEqualTo(100);
		assertThat(countWhere("uam.geographic_location", "location_id ~ '^GEO-LOC-[0-9]{9}$'")).isEqualTo(100);
		assertThat(countWhere("uam.location_geometry", "location_id ~ '^GEO-[0-9]{9}$'")).isEqualTo(100);
		assertThat(countWhere(
				"uam.geographic_address a",
				"NOT EXISTS (SELECT 1 FROM uam.location l WHERE l.id = a.location_id)")).isZero();
		assertThat(countWhere(
				"uam.geographic_address a",
				"NOT EXISTS (SELECT 1 FROM uam.geographic_location g WHERE g.id = a.geographic_location_id)")).isZero();
		assertThat(countWhere(
				"uam.geographic_location g",
				"NOT EXISTS (SELECT 1 FROM uam.location l WHERE l.id = g.location_id)")).isZero();
		assertThat(countWhere(
				"uam.location_geometry geom",
				"NOT EXISTS (SELECT 1 FROM uam.geographic_location g WHERE g.id = geom.location_id)")).isZero();
		assertThat(allocationCount(runId, "AddressLocation", "^LOC-[0-9]{9}$")).isEqualTo("100");
		assertThat(allocationCount(runId, "GeographicPointLocation", "^GEO-LOC-[0-9]{9}$")).isEqualTo("100");
		assertThat(allocationCount(runId, "GeographicAddress", "^ADDR-[0-9]{10}$")).isEqualTo("100");
		assertThat(allocationCount(runId, "GeographicLocation", "^GEO-[0-9]{9}$")).isEqualTo("100");
		assertThat(allocationCount(runId, "LocationGeometry", "^GEOM-[0-9]{9}$")).isEqualTo("100");
		assertThat(text(
				"SELECT COUNT(*) FROM migration_staging.uam_entity_stage WHERE migration_run_id = ?",
				runId)).isEqualTo("500");
		Map<String, Object> stagedAddress = stagedEntity(stagedPayloads(runId), "GeographicAddress", "ADDR100");
		assertThat(stagedAddress.get("targetIdentity")).isNull();
		assertThat(relationships(stagedAddress)).anySatisfy(link -> assertThat(link.get("parentTargetIdentity")).isNull());
	}

	@Test
	void repeatedParentsPromoteTenGeographicSubAddresses() throws Exception {
		String objectKey = "manual/sub-address-" + System.nanoTime() + ".csv";
		write(objectKey, mixedCsv());
		UUID runId = executeMigration(objectKey, 515);

		assertThat(count("uam.location")).isZero();
		assertThat(count("uam.geographic_address")).isZero();
		assertThat(count("uam.geographic_location")).isZero();
		assertThat(count("uam.location_geometry")).isZero();
		assertThat(count("uam.geographic_sub_address")).isZero();
		assertThat(allocationCount(runId)).isEqualTo("0");
		assertThat(text(
				"SELECT COUNT(*) FROM migration_staging.uam_entity_stage WHERE migration_run_id = ?",
				runId)).isEqualTo("515");
		List<Map<String, Object>> staged = stagedPayloads(runId);
		assertThat(staged.stream().filter(payload -> "GeographicSubAddress".equals(payload.get("entity"))).count())
				.isEqualTo(10);
		assertThat(staged.stream().filter(payload -> "AddressLocation".equals(payload.get("entity"))
				&& "MDU001".equals(payload.get("sourceIdentity"))).count()).isEqualTo(1);
		assertThat(staged.stream().filter(payload -> "GeographicAddress".equals(payload.get("entity"))
				&& "MDU001".equals(payload.get("sourceIdentity"))).count()).isEqualTo(1);
		Map<String, Object> stagedSubAddress = stagedEntity(staged, "GeographicSubAddress", "SUB001");
		assertThat(stagedSubAddress.get("targetIdentity")).isNull();
		assertThat(stagedSubAddress.get("targetEntity")).isEqualTo("geographic_sub_address");
		assertThat(attributes(stagedSubAddress).get("sub_unit_type")).isEqualTo("Apartment");
		assertThat(attributes(stagedSubAddress).get("sub_unit_number")).isEqualTo("1");
		assertThat(attributes(stagedSubAddress).get("sub_unit_name")).isEqualTo("Unit 1");
		assertThat(attributes(stagedSubAddress).get("level_type")).isEqualTo("Level");
		assertThat(attributes(stagedSubAddress).get("level_number")).isEqualTo("1");
		assertThat(attributes(stagedSubAddress).get("building_name")).isEqualTo("Tower");
		assertThat(relationships(stagedSubAddress)).anySatisfy(link -> {
			assertThat(link.get("parentEntity")).isEqualTo("GeographicAddress");
			assertThat(link.get("parentSourceIdentity")).isEqualTo("MDU001");
			assertThat(link.get("parentTargetIdentity")).isNull();
		});
		assertThat(relationships(stagedSubAddress)).anySatisfy(link -> {
			assertThat(link.get("parentEntity")).isEqualTo("GeographicLocation");
			assertThat(link.get("parentSourceIdentity")).isEqualTo("GEOMDU");
			assertThat(link.get("parentTargetIdentity")).isNull();
		});

		mockMvc.perform(post("/api/v1/migration-runs/{runId}/reconcile", runId).with(jwt()))
				.andExpect(status().isOk());
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/promotion/prepare", runId).with(jwt()))
				.andExpect(status().isOk());
		execute("ALTER TABLE uam.geographic_sub_address ADD CONSTRAINT ck_reject_sub_address CHECK (false)");
		Map<String, String> allocated;
		try {
			MvcResult failed = mockMvc.perform(post("/api/v1/migration-runs/{runId}/promote", runId).with(jwt()))
					.andExpect(status().isOk())
					.andReturn();
			String failedBody = failed.getResponse().getContentAsString();
			assertThat(com.jayway.jsonpath.JsonPath.read(failedBody, "$.status").toString())
					.as(failedBody)
					.isEqualTo("PROMOTION_FAILED");
			assertThat(count("uam.geographic_sub_address")).isZero();
			assertThat(count("uam.location")).isZero();
			assertThat(count("uam.geographic_address")).isZero();
			assertThat(text(
					"SELECT COUNT(*) FROM migration_staging.uam_entity_stage WHERE migration_run_id = ?",
					runId)).isEqualTo("515");
			allocated = allocatedIdentities(runId);
			assertThat(allocated).hasSize(515);
			assertThat(allocated.get("AddressLocation:MDU001")).startsWith("LOC-");
			assertThat(allocated.get("GeographicAddress:MDU001")).startsWith("ADDR-");
			assertThat(allocated.get("GeographicLocation:GEOMDU")).startsWith("GEO-");
			assertThat(allocated.keySet().stream().filter(key -> key.startsWith("GeographicSubAddress:")).count())
					.isEqualTo(10);
		} finally {
			execute("ALTER TABLE uam.geographic_sub_address DROP CONSTRAINT IF EXISTS ck_reject_sub_address");
		}

		mockMvc.perform(post("/api/v1/migration-runs/{runId}/promotion/prepare", runId).with(jwt()))
				.andExpect(status().isOk());
		MvcResult promoted = mockMvc.perform(post("/api/v1/migration-runs/{runId}/promote", runId).with(jwt()))
				.andExpect(status().isOk())
				.andReturn();
		assertThat(com.jayway.jsonpath.JsonPath.read(promoted.getResponse().getContentAsString(), "$.status").toString())
				.as(promoted.getResponse().getContentAsString())
				.isEqualTo("PROMOTED");

		assertThat(allocatedIdentities(runId)).isEqualTo(allocated);
		assertThat(allocationCount(runId)).isEqualTo("515");
		assertThat(allocationCount(runId, "AddressLocation", "^LOC-[0-9]{9}$")).isEqualTo("101");
		assertThat(allocationCount(runId, "GeographicPointLocation", "^GEO-LOC-[0-9]{9}$")).isEqualTo("101");
		assertThat(allocationCount(runId, "GeographicAddress", "^ADDR-[0-9]{10}$")).isEqualTo("101");
		assertThat(allocationCount(runId, "GeographicLocation", "^GEO-[0-9]{9}$")).isEqualTo("101");
		assertThat(allocationCount(runId, "LocationGeometry", "^GEOM-[0-9]{9}$")).isEqualTo("101");
		assertThat(allocationCount(runId, "GeographicSubAddress", "^SUB-[0-9]{9}$")).isEqualTo("10");
		assertThat(text(
				"SELECT COUNT(*) FROM migration_identity_allocation WHERE migration_run_id = ? AND entity_name = ? AND source_identity = ?",
				runId,
				"AddressLocation",
				"MDU001")).isEqualTo("1");
		assertThat(text(
				"SELECT COUNT(*) FROM migration_identity_allocation WHERE migration_run_id = ? AND entity_name = ? AND source_identity = ?",
				runId,
				"GeographicAddress",
				"MDU001")).isEqualTo("1");
		assertThat(count("uam.location")).isEqualTo(202);
		assertThat(count("uam.geographic_address")).isEqualTo(101);
		assertThat(count("uam.geographic_location")).isEqualTo(101);
		assertThat(count("uam.location_geometry")).isEqualTo(101);
		assertThat(count("uam.geographic_sub_address")).isEqualTo(10);
		String addressId = allocated.get("GeographicAddress:MDU001");
		String geographicLocationId = allocated.get("GeographicLocation:GEOMDU");
		assertThat(countWhere("uam.geographic_sub_address", "address_id = '" + addressId + "'")).isEqualTo(10);
		assertThat(countWhere(
				"uam.geographic_sub_address",
				"geographic_location_id = '" + geographicLocationId + "'")).isEqualTo(10);
		assertThat(row(
				"SELECT sub_unit_type, sub_unit_number, sub_unit_name, level_type, level_number, building_name "
						+ "FROM uam.geographic_sub_address WHERE id = '" + allocated.get("GeographicSubAddress:SUB001") + "'"))
				.containsExactly("Apartment", "1", "Unit 1", "Level", "1", "Tower");
		assertThat(text(
				"SELECT COUNT(*) FROM migration_staging.uam_entity_stage WHERE migration_run_id = ?",
				runId)).isEqualTo("515");
		assertThat(stagedEntity(stagedPayloads(runId), "GeographicSubAddress", "SUB010").get("targetIdentity")).isNull();

		mockMvc.perform(post("/api/v1/migration-runs/{runId}/promote", runId).with(jwt()))
				.andExpect(status().isOk());
		assertThat(allocatedIdentities(runId)).isEqualTo(allocated);
		assertThat(count("uam.geographic_sub_address")).isEqualTo(10);
		assertThat(count("uam.location")).isEqualTo(202);
		assertThat(count("uam.geographic_address")).isEqualTo(101);
	}

	@Test
	void failedPromotionReusesTheSameTargetIdentities() throws Exception {
		String objectKey = "manual/promote-retry-" + System.nanoTime() + ".csv";
		write(objectKey, header() + "ADDR001,GEO001,Address 001,1,Sample Street,St,Melbourne,VIC,3001,Australia,AU,-37.790000,144.910000\n");
		UUID runId = executeMigration(objectKey, 5);
		assertThat(allocationCount(runId)).isEqualTo("0");
		assertThat(count("uam.location")).isZero();
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/reconcile", runId).with(jwt()))
				.andExpect(status().isOk());
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/promotion/prepare", runId).with(jwt()))
				.andExpect(status().isOk());

		execute("ALTER TABLE uam.location ADD CONSTRAINT ck_reject_uam_promotion CHECK (false)");
		Map<String, String> allocated;
		try {
			MvcResult failed = mockMvc.perform(post("/api/v1/migration-runs/{runId}/promote", runId).with(jwt()))
					.andExpect(status().isOk())
					.andReturn();
			String failedBody = failed.getResponse().getContentAsString();
			assertThat(com.jayway.jsonpath.JsonPath.read(failedBody, "$.status").toString())
					.as(failedBody)
					.isEqualTo("PROMOTION_FAILED");
			assertThat(com.jayway.jsonpath.JsonPath.read(failedBody, "$.batches[0].promotionStatus").toString())
					.isEqualTo("FAILED");
			assertThat(count("uam.location")).isZero();
			assertThat(count("uam.geographic_address")).isZero();
			assertThat(count("uam.geographic_location")).isZero();
			assertThat(count("uam.location_geometry")).isZero();
			assertThat(text(
					"SELECT COUNT(*) FROM migration_staging.uam_entity_stage WHERE migration_run_id = ?",
					runId)).isEqualTo("5");
			assertThat(stagedPayloads(runId)).allSatisfy(payload -> assertThat(payload.get("targetIdentity")).isNull());
			allocated = allocatedIdentities(runId);
			assertThat(allocated).hasSize(5);
		} finally {
			execute("ALTER TABLE uam.location DROP CONSTRAINT IF EXISTS ck_reject_uam_promotion");
		}

		MvcResult preparedAgain = mockMvc.perform(post("/api/v1/migration-runs/{runId}/promotion/prepare", runId).with(jwt()))
				.andExpect(status().isOk())
				.andReturn();
		assertThat(com.jayway.jsonpath.JsonPath.read(preparedAgain.getResponse().getContentAsString(), "$.status").toString())
				.isEqualTo("READY");
		MvcResult promoted = mockMvc.perform(post("/api/v1/migration-runs/{runId}/promote", runId).with(jwt()))
				.andExpect(status().isOk())
				.andReturn();
		assertThat(com.jayway.jsonpath.JsonPath.read(promoted.getResponse().getContentAsString(), "$.status").toString())
				.as(promoted.getResponse().getContentAsString())
				.isEqualTo("PROMOTED");
		assertThat(allocatedIdentities(runId)).isEqualTo(allocated);
		assertThat(allocationCount(runId)).isEqualTo("5");
		assertThat(count("uam.location")).isEqualTo(2);
		assertThat(count("uam.geographic_address")).isEqualTo(1);
		assertThat(count("uam.geographic_location")).isEqualTo(1);
		assertThat(count("uam.location_geometry")).isEqualTo(1);
		assertThat(count("uam.geographic_sub_address")).isZero();

		mockMvc.perform(post("/api/v1/migration-runs/{runId}/promote", runId).with(jwt()))
				.andExpect(status().isOk());
		assertThat(allocatedIdentities(runId)).isEqualTo(allocated);
		assertThat(count("uam.location")).isEqualTo(2);
		assertThat(count("uam.geographic_address")).isEqualTo(1);
		assertThat(text(
				"SELECT COUNT(*) FROM migration_staging.uam_entity_stage WHERE migration_run_id = ?",
				runId)).isEqualTo("5");
	}

	@Test
	void retryDoesNotDuplicateCanonicalRows() throws Exception {
		List<LoadableTargetRecord> records = List.of(
				canonical("AddressLocation", "LOC-000000001", Map.of("at_type", "geographicAddress", "name", "Address 001"), List.of()),
				canonical("GeographicPointLocation", "GEO-LOC-000000001", Map.of("at_type", "geographicLocation"), List.of()),
				canonical("GeographicLocation", "GEO-000000001", Map.of("geo_json_type", "Point"), List.of(
						link("GeographicPointLocation", "GEO-LOC-000000001"))),
				canonical("GeographicAddress", "ADDR-0000000001", Map.of(
						"street_number", "1",
						"street_name", "Sample Street",
						"street_type", "St",
						"city", "Melbourne",
						"state_or_province", "VIC",
						"postcode", "3001",
						"country", "Australia",
						"country_code", "AU"), List.of(
						link("AddressLocation", "LOC-000000001"),
						link("GeographicLocation", "GEO-000000001"))),
				canonical("LocationGeometry", "GEOM-000000001", Map.of(
						"geometry_type", "Point",
						"x", "144.910000",
						"y", "-37.790000"), List.of(
						link("GeographicLocation", "GEO-000000001"))));
		canonicalConnector.load(context("uam"), records);
		canonicalConnector.load(context("uam"), records);
		assertThat(count("uam.location")).isEqualTo(2);
		assertThat(count("uam.geographic_address")).isEqualTo(1);
		assertThat(count("uam.geographic_location")).isEqualTo(1);
		assertThat(count("uam.location_geometry")).isEqualTo(1);
	}

	@Test
	void failedBatchRollsBackAndRetryInsertsOnce() throws Exception {
		execute("DROP SCHEMA IF EXISTS uam_fail CASCADE");
		execute("CREATE SCHEMA uam_fail");
		execute("""
				CREATE TABLE uam_fail.location (
				  id varchar(64) PRIMARY KEY,
				  at_type varchar(64) NOT NULL,
				  created_at timestamptz NOT NULL,
				  CONSTRAINT ck_uam_fail_location CHECK (id <> 'LOC-000000002')
				)
				""");
		List<LoadableTargetRecord> records = List.of(
				canonical("AddressLocation", "LOC-000000001", Map.of("at_type", "geographicAddress"), List.of()),
				canonical("AddressLocation", "LOC-000000002", Map.of("at_type", "geographicAddress"), List.of()));
		assertThatThrownBy(() -> canonicalConnector.load(context("uam_fail"), records))
				.isInstanceOf(TargetInfrastructureException.class);
		assertThat(count("uam_fail.location")).isZero();

		execute("ALTER TABLE uam_fail.location DROP CONSTRAINT ck_uam_fail_location");
		canonicalConnector.load(context("uam_fail"), records);
		canonicalConnector.load(context("uam_fail"), records);
		assertThat(count("uam_fail.location")).isEqualTo(2);
	}

	@Test
	void stagingLoadDoesNotWriteCanonicalTables() throws Exception {
		execute("CREATE SCHEMA IF NOT EXISTS migration_staging");
		execute("""
				CREATE TABLE IF NOT EXISTS migration_staging.address_load (
				  migration_run_id UUID NOT NULL,
				  migration_batch_id BIGINT NOT NULL,
				  source_record_number BIGINT NOT NULL,
				  target_payload JSONB NOT NULL,
				  idempotency_key VARCHAR(500),
				  loaded_at TIMESTAMPTZ NOT NULL,
				  UNIQUE (migration_run_id, migration_batch_id, source_record_number)
				)
				""");
		UUID runId = UUID.randomUUID();
		stagingConnector.load(
				new MigrationTargetContext(
						runId,
						11L,
						1,
						"POSTGRESQL",
						"staged",
						LoadMode.BULK,
						Map.of("target", Map.of(
								"schema", "migration_staging",
								"stagingTable", "address_load",
								"mergeStrategy", "NONE"))),
				List.of(new LoadableTargetRecord(1L, new TargetRecord(Map.of("streetName", "Only Street")), "flat-1")));
		assertThat(count("uam.location")).isZero();
		assertThat(count("uam.geographic_address")).isZero();
		assertThat(text(
				"SELECT COUNT(*) FROM migration_staging.address_load WHERE migration_run_id = ?",
				runId)).isEqualTo("1");
	}

	private UUID migrate(String objectKey, int expectedSuccess) throws Exception {
		String body = configuration(objectKey);
		MvcResult created = mockMvc.perform(post("/api/v1/migration-configurations")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isCreated())
				.andReturn();
		String definitionId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.definition.id");
		transactionTemplate.executeWithoutResult(status -> {
			MigrationDefinition definition = migrationDefinitionRepository.findById(UUID.fromString(definitionId)).orElseThrow();
			definition.setStatus(MigrationConfigStatus.ACTIVE);
		});
		MvcResult run = mockMvc.perform(post("/api/v1/migrations/{id}/runs", definitionId)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isCreated())
				.andReturn();
		String runId = com.jayway.jsonpath.JsonPath.read(run.getResponse().getContentAsString(), "$.id");
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/prepare", runId).with(jwt()))
				.andExpect(status().isOk());
		int success = 0;
		for (int attempt = 0; attempt < 5; attempt++) {
			MvcResult processed = mockMvc.perform(post("/api/v1/migration-runs/{runId}/process-next-batch", runId).with(jwt()))
					.andReturn();
			if (processed.getResponse().getStatus() == 409) {
				break;
			}
			assertThat(processed.getResponse().getStatus())
					.as(processed.getResponse().getContentAsString())
					.isEqualTo(200);
			String response = processed.getResponse().getContentAsString();
			assertThat(com.jayway.jsonpath.JsonPath.read(response, "$.status").toString())
					.as(response + " " + errors(UUID.fromString(runId)))
					.isEqualTo("COMPLETED");
			success += ((Number) com.jayway.jsonpath.JsonPath.read(response, "$.successRecords")).intValue();
		}
		assertThat(success).as(errors(UUID.fromString(runId))).isEqualTo(expectedSuccess);
		return UUID.fromString(runId);
	}

	private List<Map<String, Object>> stagedPayloads(UUID runId) throws Exception {
		com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
		List<Map<String, Object>> payloads = new java.util.ArrayList<>();
		try (Connection connection = targetDataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT target_payload::text FROM migration_staging.uam_entity_stage WHERE migration_run_id = ?")) {
			statement.setObject(1, runId);
			try (ResultSet resultSet = statement.executeQuery()) {
				while (resultSet.next()) {
					payloads.add(mapper.readValue(
							resultSet.getString(1),
							new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
							}));
				}
			}
		}
		return payloads;
	}

	private static Map<String, Object> stagedEntity(List<Map<String, Object>> payloads, String entity, String source) {
		return payloads.stream()
				.filter(payload -> entity.equals(payload.get("entity")) && source.equals(payload.get("sourceIdentity")))
				.findFirst()
				.orElseThrow();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> attributes(Map<String, Object> payload) {
		return (Map<String, Object>) payload.get("attributes");
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> relationships(Map<String, Object> payload) {
		return (List<Map<String, Object>>) payload.get("relationships");
	}

	private String errors(UUID runId) throws Exception {
		StringBuilder builder = new StringBuilder();
		try (Connection connection = targetDataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT error_code, error_message FROM migration_error WHERE migration_run_id = ?")) {
			statement.setObject(1, runId);
			try (ResultSet resultSet = statement.executeQuery()) {
				while (resultSet.next()) {
					builder.append(resultSet.getString(1))
							.append(' ')
							.append(resultSet.getString(2))
							.append('\n');
				}
			}
		} catch (Exception ex) {
			return ex.getMessage();
		}
		return builder.toString();
	}

	private UUID executeMigration(String objectKey, int expectedSuccess) throws Exception {
		String body = configuration(objectKey, promotionConfiguration());
		MvcResult created = mockMvc.perform(post("/api/v1/migration-configurations")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isCreated())
				.andReturn();
		String definitionId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.definition.id");
		transactionTemplate.executeWithoutResult(status -> {
			MigrationDefinition definition = migrationDefinitionRepository.findById(UUID.fromString(definitionId)).orElseThrow();
			definition.setStatus(MigrationConfigStatus.ACTIVE);
		});
		MvcResult executed = mockMvc.perform(post("/api/v1/migrations/{migrationId}/execute", definitionId)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isCreated())
				.andReturn();
		String response = executed.getResponse().getContentAsString();
		assertThat(com.jayway.jsonpath.JsonPath.read(response, "$.status").toString())
				.as(response + " " + errors(UUID.fromString(
						com.jayway.jsonpath.JsonPath.read(response, "$.id"))))
				.isEqualTo("COMPLETED");
		assertThat(((Number) com.jayway.jsonpath.JsonPath.read(response, "$.successRecords")).intValue())
				.as(response)
				.isEqualTo(expectedSuccess);
		return UUID.fromString(com.jayway.jsonpath.JsonPath.read(response, "$.id"));
	}

	private static String configuration(String objectKey) {
		return configuration(objectKey, stagingConfiguration());
	}

	private static String configuration(String objectKey, String definitionConfiguration) {
		String name = "uam-" + System.nanoTime();
		return """
				{
				  "definition": {
				    "name": "%s",
				    "version": "1",
				    "sourceType": "FILE",
				    "targetType": "POSTGRESQL",
				    "targetEntity": "location",
				    "batchSize": 1000,
				    "loadMode": "BULK",
				    "configuration": %s
				  },
				  "mapping": {
				    "name": "%s-mapping",
				    "version": "1",
				    "sourceType": "FILE",
				    "targetType": "UAM_POSTGRESQL",
				    "configuration": {
				      "modelType": "ENTITY_RELATIONSHIP",
				      "rootEntity": "AddressLocation",
				      "entities": [
				        {"name": "AddressLocation", "targetEntity": "location", "sourceKey": "address_id", "targetKey": "id", "targetIdentity": {"strategy": "PREFIX_SEQUENCE", "prefix": "LOC-", "sequenceLength": 9}},
				        {"name": "GeographicPointLocation", "targetEntity": "location", "sourceKey": "geo_id", "targetKey": "id", "targetIdentity": {"strategy": "PREFIX_SEQUENCE", "prefix": "GEO-LOC-", "sequenceLength": 9}},
				        {"name": "GeographicAddress", "targetEntity": "geographic_address", "sourceKey": "address_id", "targetKey": "id", "targetIdentity": {"strategy": "PREFIX_SEQUENCE", "prefix": "ADDR-", "sequenceLength": 10}},
				        {"name": "GeographicLocation", "targetEntity": "geographic_location", "sourceKey": "geo_id", "targetKey": "id", "targetIdentity": {"strategy": "PREFIX_SEQUENCE", "prefix": "GEO-", "sequenceLength": 9}},
				        {"name": "LocationGeometry", "targetEntity": "location_geometry", "sourceKey": "geo_id", "targetKey": "id", "targetIdentity": {"strategy": "PREFIX_SEQUENCE", "prefix": "GEOM-", "sequenceLength": 9}},
				        {"name": "GeographicSubAddress", "targetEntity": "geographic_sub_address", "sourceKey": "sub_address_id", "targetKey": "id", "targetIdentity": {"strategy": "PREFIX_SEQUENCE", "prefix": "SUB-", "sequenceLength": 9}}
				      ],
				      "relationships": [
				        {"parent": "AddressLocation", "child": "GeographicAddress", "cardinality": "ONE_TO_ONE", "keys": [{"parentField": "address_id", "childField": "address_id"}]},
				        {"parent": "AddressLocation", "child": "GeographicPointLocation", "cardinality": "ONE_TO_ONE", "keys": [{"parentField": "geo_id", "childField": "geo_id"}]},
				        {"parent": "GeographicPointLocation", "child": "GeographicLocation", "cardinality": "ONE_TO_ONE", "keys": [{"parentField": "geo_id", "childField": "geo_id"}]},
				        {"parent": "GeographicPointLocation", "child": "LocationGeometry", "cardinality": "ONE_TO_ONE", "keys": [{"parentField": "geo_id", "childField": "geo_id"}]},
				        {"parent": "GeographicLocation", "child": "GeographicAddress", "cardinality": "ONE_TO_ONE", "keys": [{"parentField": "geo_id", "childField": "geo_id"}]},
				        {"parent": "GeographicLocation", "child": "LocationGeometry", "cardinality": "ONE_TO_ONE", "keys": [{"parentField": "geo_id", "childField": "geo_id"}]},
				        {"parent": "GeographicAddress", "child": "GeographicSubAddress", "cardinality": "ONE_TO_MANY", "keys": [{"parentField": "address_id", "childField": "address_id"}]},
				        {"parent": "GeographicLocation", "child": "GeographicSubAddress", "cardinality": "ONE_TO_MANY", "keys": [{"parentField": "geo_id", "childField": "geo_id"}]}
				      ]
				    }
				  },
				  "rules": [
				    {"sequenceNo": 1, "entity": "AddressLocation", "targetField": "at_type", "transformationType": "DEFAULT", "defaultValue": "geographicAddress", "mandatory": true, "enabled": true},
				    {"sequenceNo": 2, "entity": "AddressLocation", "sourceField": "address_name", "targetField": "name", "transformationType": "DIRECT", "mandatory": true, "enabled": true},
				    {"sequenceNo": 3, "entity": "GeographicPointLocation", "targetField": "at_type", "transformationType": "DEFAULT", "defaultValue": "geographicLocation", "mandatory": true, "enabled": true},
				    {"sequenceNo": 4, "entity": "GeographicAddress", "sourceField": "street_number", "targetField": "street_number", "transformationType": "DIRECT", "enabled": true},
				    {"sequenceNo": 5, "entity": "GeographicAddress", "sourceField": "street_name", "targetField": "street_name", "transformationType": "DIRECT", "enabled": true},
				    {"sequenceNo": 6, "entity": "GeographicAddress", "sourceField": "city", "targetField": "city", "transformationType": "DIRECT", "enabled": true},
				    {"sequenceNo": 7, "entity": "GeographicAddress", "sourceField": "state", "targetField": "state_or_province", "transformationType": "DIRECT", "enabled": true},
				    {"sequenceNo": 8, "entity": "GeographicAddress", "sourceField": "postcode", "targetField": "postcode", "transformationType": "DIRECT", "enabled": true},
				    {"sequenceNo": 9, "entity": "GeographicAddress", "sourceField": "country", "targetField": "country", "transformationType": "DIRECT", "enabled": true},
				    {"sequenceNo": 14, "entity": "GeographicAddress", "sourceField": "street_type", "targetField": "street_type", "transformationType": "DIRECT", "enabled": true},
				    {"sequenceNo": 15, "entity": "GeographicAddress", "sourceField": "country_code", "targetField": "country_code", "transformationType": "DIRECT", "enabled": true},
				    {"sequenceNo": 10, "entity": "GeographicLocation", "targetField": "geo_json_type", "transformationType": "DEFAULT", "defaultValue": "Point", "mandatory": true, "enabled": true},
				    {"sequenceNo": 11, "entity": "LocationGeometry", "targetField": "geometry_type", "transformationType": "DEFAULT", "defaultValue": "Point", "mandatory": true, "enabled": true},
				    {"sequenceNo": 12, "entity": "LocationGeometry", "sourceField": "longitude", "targetField": "x", "transformationType": "DIRECT", "enabled": true},
				    {"sequenceNo": 13, "entity": "LocationGeometry", "sourceField": "latitude", "targetField": "y", "transformationType": "DIRECT", "enabled": true},
				    {"sequenceNo": 16, "entity": "GeographicSubAddress", "sourceField": "sub_unit_type", "targetField": "sub_unit_type", "transformationType": "DIRECT", "enabled": true},
				    {"sequenceNo": 17, "entity": "GeographicSubAddress", "sourceField": "sub_unit_number", "targetField": "sub_unit_number", "transformationType": "DIRECT", "enabled": true},
				    {"sequenceNo": 18, "entity": "GeographicSubAddress", "sourceField": "sub_unit_name", "targetField": "sub_unit_name", "transformationType": "DIRECT", "enabled": true},
				    {"sequenceNo": 19, "entity": "GeographicSubAddress", "sourceField": "level_type", "targetField": "level_type", "transformationType": "DIRECT", "enabled": true},
				    {"sequenceNo": 20, "entity": "GeographicSubAddress", "sourceField": "level_number", "targetField": "level_number", "transformationType": "DIRECT", "enabled": true},
				    {"sequenceNo": 21, "entity": "GeographicSubAddress", "sourceField": "building_name", "targetField": "building_name", "transformationType": "DIRECT", "enabled": true},
				    {"sequenceNo": 22, "entity": "GeographicSubAddress", "targetField": "private_street_name", "transformationType": "DEFAULT", "defaultValue": "", "enabled": true},
				    {"sequenceNo": 23, "entity": "GeographicSubAddress", "targetField": "private_street_number", "transformationType": "DEFAULT", "defaultValue": "", "enabled": true}
				  ],
				  "source": {
				    "name": "%s-source",
				    "sourceType": "FILE",
				    "enabled": true,
				    "configuration": {
				      "provider": "FILE_SYSTEM",
				      "fileFormat": "CSV",
				      "objectKey": "%s",
				      "hasHeader": true,
				      "delimiter": ","
				    }
				  }
				}
				""".formatted(name, definitionConfiguration, name, name, objectKey);
	}

	private static String promotionConfiguration() {
		return """
				{
				  "target": {
				    "mode": "UAM",
				    "schema": "uam",
				    "stagingSchema": "migration_staging",
				    "stagingTable": "uam_entity_stage",
				    "mergeStrategy": "NONE"
				  },
				  "tables": {
				    "AddressLocation": "location",
				    "GeographicPointLocation": "location",
				    "GeographicAddress": "geographic_address",
				    "GeographicLocation": "geographic_location",
				    "LocationGeometry": "location_geometry",
				    "GeographicSubAddress": "geographic_sub_address"
				  },
				  "loadOrder": [
				    "AddressLocation",
				    "GeographicPointLocation",
				    "GeographicLocation",
				    "GeographicAddress",
				    "LocationGeometry",
				    "GeographicSubAddress"
				  ],
				  "foreignKeys": [
				    {"entity": "GeographicAddress", "parent": "AddressLocation", "column": "location_id"},
				    {"entity": "GeographicAddress", "parent": "GeographicLocation", "column": "geographic_location_id"},
				    {"entity": "GeographicLocation", "parent": "GeographicPointLocation", "column": "location_id"},
				    {"entity": "LocationGeometry", "parent": "GeographicLocation", "column": "location_id"},
				    {"entity": "GeographicSubAddress", "parent": "GeographicAddress", "column": "address_id"},
				    {"entity": "GeographicSubAddress", "parent": "GeographicLocation", "column": "geographic_location_id"}
				  ],
				  "loadTimestampColumns": [
				    {"table": "location", "column": "created_at"}
				  ],
				  "columnTypes": [
				    {"table": "location", "column": "created_at", "type": "timestamptz"},
				    {"table": "location_geometry", "column": "x", "type": "numeric"},
				    {"table": "location_geometry", "column": "y", "type": "numeric"}
				  ],
				  "promotion": {"enabled": true}
				}
				""";
	}

	private static String stagingConfiguration() {
		return """
				{
				  "target": {
				    "schema": "migration_staging",
				    "stagingTable": "uam_entity_stage",
				    "mergeStrategy": "NONE"
				  }
				}
				""";
	}

	private static String canonicalConfiguration(String schema) {
		return """
				{
				  "target": {"mode": "UAM", "schema": "%s"},
				  "tables": {
				    "AddressLocation": "location",
				    "GeographicPointLocation": "location",
				    "GeographicAddress": "geographic_address",
				    "GeographicLocation": "geographic_location",
				    "LocationGeometry": "location_geometry"
				  },
				  "loadOrder": [
				    "AddressLocation",
				    "GeographicPointLocation",
				    "GeographicLocation",
				    "GeographicAddress",
				    "LocationGeometry"
				  ],
				  "foreignKeys": [
				    {"entity": "GeographicAddress", "parent": "AddressLocation", "column": "location_id"},
				    {"entity": "GeographicAddress", "parent": "GeographicLocation", "column": "geographic_location_id"},
				    {"entity": "GeographicLocation", "parent": "GeographicPointLocation", "column": "location_id"},
				    {"entity": "LocationGeometry", "parent": "GeographicLocation", "column": "location_id"}
				  ],
				  "loadTimestampColumns": [
				    {"table": "location", "column": "created_at"}
				  ],
				  "columnTypes": [
				    {"table": "location", "column": "created_at", "type": "timestamptz"},
				    {"table": "location_geometry", "column": "x", "type": "numeric"},
				    {"table": "location_geometry", "column": "y", "type": "numeric"}
				  ]
				}
				""".formatted(schema);
	}

	private MigrationTargetContext context(String schema) {
		return new MigrationTargetContext(
				UUID.randomUUID(),
				1L,
				1,
				UamPostgreSqlTargetConnector.TARGET_TYPE,
				"location",
				LoadMode.BULK,
				Map.of(
						"target", Map.of("mode", "UAM", "schema", schema),
						"tables", Map.of(
								"AddressLocation", "location",
								"GeographicPointLocation", "location",
								"GeographicLocation", "geographic_location",
								"GeographicAddress", "geographic_address",
								"LocationGeometry", "location_geometry"),
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
						"loadTimestampColumns", List.of(
								Map.of("table", "location", "column", "created_at")),
						"columnTypes", List.of(
								Map.of("table", "location", "column", "created_at", "type", "timestamptz"),
								Map.of("table", "location_geometry", "column", "x", "type", "numeric"),
								Map.of("table", "location_geometry", "column", "y", "type", "numeric"))));
	}

	private static LoadableTargetRecord canonical(
			String entity,
			String targetIdentity,
			Map<String, Object> attributes,
			List<Map<String, Object>> relationships) {
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("entity", entity);
		fields.put("targetKey", "id");
		fields.put("targetIdentity", targetIdentity);
		fields.put("attributes", new LinkedHashMap<>(attributes));
		fields.put("relationships", relationships);
		return new LoadableTargetRecord(1L, new TargetRecord(fields), entity + ":" + targetIdentity);
	}

	private static Map<String, Object> link(String entity, String targetIdentity) {
		Map<String, Object> parent = new LinkedHashMap<>();
		parent.put("parentEntity", entity);
		parent.put("parentTargetIdentity", targetIdentity);
		return parent;
	}

	private static String header() {
		return "address_id,geo_id,address_name,street_number,street_name,street_type,city,state,postcode,country,country_code,latitude,longitude\n";
	}

	private static String mixedCsv() {
		StringBuilder builder = new StringBuilder(
				"address_id,geo_id,address_name,street_number,street_name,street_type,city,state,postcode,country,country_code,latitude,longitude,sub_address_id,sub_unit_type,sub_unit_number,sub_unit_name,level_type,level_number,building_name\n");
		for (int index = 1; index <= 100; index++) {
			String number = String.format("%03d", index);
			builder.append("ADDR").append(number)
					.append(",GEO").append(number)
					.append(",Address ").append(number)
					.append(',').append(index)
					.append(",Sample Street,St,Melbourne,VIC,")
					.append(3000 + index)
					.append(",Australia,AU,")
					.append(String.format(Locale.US, "%.6f", -37.8 + (index * 0.01)))
					.append(',')
					.append(String.format(Locale.US, "%.6f", 144.9 + (index * 0.01)))
					.append(",,,,,,,\n");
		}
		for (int index = 1; index <= 10; index++) {
			builder.append("MDU001,GEOMDU,MDU 001,10,Sample Street,St,Melbourne,VIC,3999,Australia,AU,-37.800000,144.900000,SUB")
					.append(String.format("%03d", index))
					.append(",Apartment,")
					.append(index)
					.append(",Unit ")
					.append(index)
					.append(",Level,")
					.append(index)
					.append(",Tower\n");
		}
		return builder.toString();
	}

	private static String csv(int rows) {
		StringBuilder builder = new StringBuilder(header());
		for (int index = 1; index <= rows; index++) {
			String number = String.format("%03d", index);
			builder.append("ADDR").append(number)
					.append(",GEO").append(number)
					.append(",Address ").append(number)
					.append(',').append(index)
					.append(",Sample Street,St,Melbourne,VIC,")
					.append(3000 + index)
					.append(",Australia,AU,")
					.append(String.format(Locale.US, "%.6f", -37.8 + (index * 0.01)))
					.append(',')
					.append(String.format(Locale.US, "%.6f", 144.9 + (index * 0.01)))
					.append('\n');
		}
		return builder.toString();
	}

	private void write(String objectKey, String content) throws Exception {
		Path path = storageRoot.resolve(objectKey);
		Files.createDirectories(path.getParent());
		Files.writeString(path, content);
	}

	private void execute(String sql) throws Exception {
		try (Connection connection = targetDataSource.getConnection()) {
			connection.createStatement().execute(sql);
		}
	}

	private String allocationCount(UUID runId) throws Exception {
		return text("SELECT COUNT(*) FROM migration_identity_allocation WHERE migration_run_id = ?", runId);
	}

	private String allocationCount(UUID runId, String entityName, String pattern) throws Exception {
		return text(
				"SELECT COUNT(*) FROM migration_identity_allocation WHERE migration_run_id = ? AND entity_name = ? AND target_identity ~ ?",
				runId,
				entityName,
				pattern);
	}

	private Map<String, String> allocatedIdentities(UUID runId) throws Exception {
		Map<String, String> identities = new LinkedHashMap<>();
		try (Connection connection = targetDataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT entity_name, source_identity, target_identity FROM migration_identity_allocation WHERE migration_run_id = ?")) {
			statement.setObject(1, runId);
			try (ResultSet resultSet = statement.executeQuery()) {
				while (resultSet.next()) {
					identities.put(resultSet.getString(1) + ":" + resultSet.getString(2), resultSet.getString(3));
				}
			}
		}
		return identities;
	}

	private int countWhere(String table, String predicate) throws Exception {
		return count(table + " WHERE " + predicate);
	}

	private int count(String table) throws Exception {
		try (Connection connection = targetDataSource.getConnection();
				ResultSet resultSet = connection.createStatement().executeQuery("SELECT COUNT(*) FROM " + table)) {
			resultSet.next();
			return resultSet.getInt(1);
		}
	}

	private List<String> row(String sql) throws Exception {
		try (Connection connection = targetDataSource.getConnection();
				ResultSet resultSet = connection.createStatement().executeQuery(sql)) {
			assertThat(resultSet.next()).isTrue();
			int columns = resultSet.getMetaData().getColumnCount();
			String[] values = new String[columns];
			for (int index = 0; index < columns; index++) {
				values[index] = resultSet.getString(index + 1);
			}
			return java.util.Arrays.asList(values);
		}
	}

	private String text(String sql, Object... parameters) throws Exception {
		try (Connection connection = targetDataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(sql)) {
			for (int index = 0; index < parameters.length; index++) {
				statement.setObject(index + 1, parameters[index]);
			}
			try (ResultSet resultSet = statement.executeQuery()) {
				assertThat(resultSet.next()).isTrue();
				return resultSet.getString(1);
			}
		}
	}
}
