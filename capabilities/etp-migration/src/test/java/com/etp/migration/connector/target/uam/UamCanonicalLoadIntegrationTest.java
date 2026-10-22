package com.etp.migration.connector.target.uam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.etp.migration.connector.source.sftp.SftpCredentialProperties;
import com.etp.migration.run.PhaseAwait;
import com.etp.migration.connector.target.postgresql.PostgreSqlConnectionProperties;
import com.etp.migration.connector.target.postgresql.PostgreSqlConnectionRegistry;
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
import java.security.PublicKey;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
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
	private SftpCredentialProperties sftpProperties;

	@Autowired
	private Environment environment;

	@Autowired
	private PostgreSqlConnectionProperties connectionProperties;

	@Autowired
	private PostgreSqlConnectionRegistry connectionRegistry;

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
				  private_street_name varchar(128),
				  private_street_number varchar(32),
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
		execute("""
				CREATE INDEX IF NOT EXISTS idx_uam_entity_stage_run_batch
				ON migration_staging.uam_entity_stage (migration_run_id, migration_batch_id)
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

		MvcResult reconciled = PhaseAwait.reconciled(mockMvc, runId)
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

		MvcResult prepared = PhaseAwait.prepared(mockMvc, runId)
				.andReturn();
		String preparedBody = prepared.getResponse().getContentAsString();
		assertThat(com.jayway.jsonpath.JsonPath.read(preparedBody, "$.status").toString()).isEqualTo("READY");
		assertThat(((Number) com.jayway.jsonpath.JsonPath.read(preparedBody, "$.eligibleRecords")).intValue())
				.isEqualTo(500);

		MvcResult promoted = PhaseAwait.promoted(mockMvc, runId)
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

		PhaseAwait.reconciled(mockMvc, runId);
		PhaseAwait.prepared(mockMvc, runId);
		execute("ALTER TABLE uam.geographic_sub_address ADD CONSTRAINT ck_reject_sub_address CHECK (false)");
		Map<String, String> allocated;
		try {
			MvcResult failed = PhaseAwait.promoted(mockMvc, runId)
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

		PhaseAwait.prepared(mockMvc, runId);
		MvcResult promoted = PhaseAwait.promoted(mockMvc, runId)
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
		assertThat(row(
				"SELECT private_street_name, private_street_number "
						+ "FROM uam.geographic_sub_address WHERE id = '" + allocated.get("GeographicSubAddress:SUB001") + "'"))
				.containsExactly(null, null);
		assertThat(text(
				"SELECT COUNT(*) FROM migration_staging.uam_entity_stage WHERE migration_run_id = ?",
				runId)).isEqualTo("515");
		assertThat(stagedEntity(stagedPayloads(runId), "GeographicSubAddress", "SUB010").get("targetIdentity")).isNull();

		PhaseAwait.promoted(mockMvc, runId);
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
		PhaseAwait.reconciled(mockMvc, runId);
		PhaseAwait.prepared(mockMvc, runId);

		execute("ALTER TABLE uam.location ADD CONSTRAINT ck_reject_uam_promotion CHECK (false)");
		Map<String, String> allocated;
		try {
			MvcResult failed = PhaseAwait.promoted(mockMvc, runId)
					.andReturn();
			String failedBody = failed.getResponse().getContentAsString();
			assertThat(com.jayway.jsonpath.JsonPath.read(failedBody, "$.status").toString())
					.as(failedBody)
					.isEqualTo("PROMOTION_FAILED");
			assertThat(com.jayway.jsonpath.JsonPath.read(failedBody, "$.batchCounts.FAILED").toString())
					.isEqualTo("1");
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

		MvcResult preparedAgain = PhaseAwait.prepared(mockMvc, runId)
				.andReturn();
		assertThat(com.jayway.jsonpath.JsonPath.read(preparedAgain.getResponse().getContentAsString(), "$.status").toString())
				.isEqualTo("READY");
		MvcResult promoted = PhaseAwait.promoted(mockMvc, runId)
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

		PhaseAwait.promoted(mockMvc, runId);
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
						link("GeographicPointLocation", "GEO-LOC-000000001", "location_id", "id"))),
				canonical("GeographicAddress", "ADDR-0000000001", Map.of(
						"street_number", "1",
						"street_name", "Sample Street",
						"street_type", "St",
						"city", "Melbourne",
						"state_or_province", "VIC",
						"postcode", "3001",
						"country", "Australia",
						"country_code", "AU"), List.of(
						link("AddressLocation", "LOC-000000001", "location_id", "id"),
						link("GeographicLocation", "GEO-000000001", "geographic_location_id", "id"))),
				canonical("LocationGeometry", "GEOM-000000001", Map.of(
						"geometry_type", "Point",
						"x", "144.910000",
						"y", "-37.790000"), List.of(
						link("GeographicLocation", "GEO-000000001", "location_id", "id"))));
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
				.isInstanceOf(TargetInfrastructureException.class)
				.hasMessageContaining("check_violation (SQLState 23514)")
				.hasMessageContaining("table uam_fail.location")
				.hasMessageContaining("constraint ck_uam_fail_location")
				.hasMessageNotContaining("LOC-000000002")
				.hasMessageNotContaining("Failing row");
		assertThat(count("uam_fail.location")).isZero();

		execute("ALTER TABLE uam_fail.location DROP CONSTRAINT ck_uam_fail_location");
		canonicalConnector.load(context("uam_fail"), records);
		canonicalConnector.load(context("uam_fail"), records);
		assertThat(count("uam_fail.location")).isEqualTo(2);
	}

	@Test
	void catalogConvertsSchemaTypesAndAppliesDefaultsPerRow() throws Exception {
		try (Connection connection = targetDataSource.getConnection()) {
			Map<String, UamTargetColumns.Column> location = UamTargetColumns.load(connection, "uam", "location");
			assertThat(location.get("id").udtName()).isEqualTo("varchar");
			assertThat(location.get("description").udtName()).isEqualTo("text");
			assertThat(location.get("created_at").udtName()).isEqualTo("timestamptz");
			assertThat(location.get("created_at").nullable()).isFalse();
			assertThat(location.get("status").defaultExpression()).contains("PLANNED");
			assertThat(location.get("version").defaultExpression()).contains("1.0");

			Map<String, UamTargetColumns.Column> geometry = UamTargetColumns.load(connection, "uam", "location_geometry");
			assertThat(geometry.get("x").udtName()).isEqualTo("numeric");
			assertThat(geometry.get("raw_geojson").udtName()).isEqualTo("jsonb");
			assertThat(UamTargetColumns.load(connection, "uam", "geographic_address").get("location_id").udtName())
					.isEqualTo("varchar");
		}

		Map<String, Object> geojson = new LinkedHashMap<>();
		geojson.put("type", "Point");
		List<LoadableTargetRecord> records = List.of(
				canonical("AddressLocation", "LOC-1", Map.of(
						"at_type", "geographicAddress",
						"name", "Main",
						"status", "   ",
						"version", " ",
						"created_at", "2020-01-02T03:04:05Z"), List.of()),
				canonical("AddressLocation", "LOC-2", Map.of(
						"at_type", "geographicAddress",
						"name", "Other",
						"status", "ACTIVE",
						"version", "2.0",
						"created_at", " "), List.of()),
				canonical("GeographicPointLocation", "GEO-LOC-1", Map.of(
						"at_type", "geographicLocation",
						"created_at", " "), List.of()),
				canonical("GeographicLocation", "GEO-1", Map.of(
						"geo_json_type", "Point"), List.of(
						link("GeographicPointLocation", "GEO-LOC-1", "location_id", "id"))),
				canonical("LocationGeometry", "GEOM-1", attributes(
						"geometry_type", "Point",
						"x", "144.910000",
						"y", "-37.790000",
						"z", " ",
						"raw_geojson", geojson), List.of(
						link("GeographicLocation", "GEO-1", "location_id", "id"))));
		canonicalConnector.load(contextWithoutColumnTypes(), records);

		assertThat(row("""
				SELECT status, version, to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS'), name
				FROM uam.location WHERE id = 'LOC-1'
				""")).containsExactly("PLANNED", "1.0", "2020-01-02 03:04:05", "Main");
		List<String> filled = row("""
				SELECT status, version, to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS')
				FROM uam.location WHERE id = 'LOC-2'
				""");
		assertThat(filled.get(0)).isEqualTo("ACTIVE");
		assertThat(filled.get(1)).isEqualTo("2.0");
		assertThat(filled.get(2)).doesNotContain("2020-01-02");
		assertThat(text("SELECT location_id FROM uam.geographic_location WHERE id = 'GEO-1'")).isEqualTo("GEO-LOC-1");
		assertThat(row("""
				SELECT location_id, x::text, y::text, z::text, raw_geojson->>'type'
				FROM uam.location_geometry WHERE id = 'GEOM-1'
				""")).containsExactly("GEO-1", "144.910000", "-37.790000", null, "Point");

		assertThatThrownBy(() -> canonicalConnector.load(contextWithoutColumnTypes(), List.of(
				canonical("AddressLocation", "LOC-3", Map.of(
						"at_type", "geographicAddress",
						"created_at", " "), List.of()),
				canonical("GeographicPointLocation", "GEO-LOC-2", Map.of(
						"at_type", "geographicLocation",
						"created_at", " "), List.of()),
				canonical("GeographicLocation", "GEO-2", Map.of("geo_json_type", "Point"), List.of(
						link("GeographicPointLocation", "GEO-LOC-2", "location_id", "id"))),
				canonical("LocationGeometry", "GEOM-2", Map.of(
						"geometry_type", "Point",
						"x", "north"), List.of(
						link("GeographicLocation", "GEO-2", "location_id", "id"))))))
				.isInstanceOf(TargetInfrastructureException.class)
				.hasMessageContaining("column 'x'")
				.hasMessageContaining("numeric");
		assertThat(count("uam.location_geometry")).isEqualTo(1);

		execute("""
				CREATE TABLE uam.type_probe (
				  id varchar(64) PRIMARY KEY,
				  code char(3),
				  quantity integer,
				  whole bigint,
				  ratio double precision,
				  active boolean,
				  external_id uuid,
				  observed_on date,
				  updated_at timestamp,
				  payload json,
				  note text,
				  status varchar(32) DEFAULT 'PLANNED',
				  seq_id integer GENERATED BY DEFAULT AS IDENTITY,
				  legacy_seq serial,
				  search_text text GENERATED ALWAYS AS (note) STORED
				)
				""");
		execute("""
				CREATE TABLE uam.type_probe_always (
				  id varchar(64) PRIMARY KEY,
				  note text,
				  always_id integer GENERATED ALWAYS AS IDENTITY
				)
				""");
		try (Connection connection = targetDataSource.getConnection()) {
			Map<String, UamTargetColumns.Column> probe = UamTargetColumns.load(connection, "uam", "type_probe");
			assertThat(probe.get("code").udtName()).isEqualTo("bpchar");
			assertThat(probe.get("quantity").udtName()).isEqualTo("int4");
			assertThat(probe.get("whole").udtName()).isEqualTo("int8");
			assertThat(probe.get("ratio").udtName()).isEqualTo("float8");
			assertThat(probe.get("active").udtName()).isEqualTo("bool");
			assertThat(probe.get("external_id").udtName()).isEqualTo("uuid");
			assertThat(probe.get("observed_on").udtName()).isEqualTo("date");
			assertThat(probe.get("updated_at").udtName()).isEqualTo("timestamp");
			assertThat(probe.get("payload").udtName()).isEqualTo("json");
			assertThat(probe.get("seq_id").identity()).isTrue();
			assertThat(probe.get("seq_id").identityGeneration()).isEqualTo("BY DEFAULT");
			assertThat(probe.get("legacy_seq").identity()).isFalse();
			assertThat(probe.get("legacy_seq").defaultExpression()).contains("nextval");
			assertThat(probe.get("search_text").generatedAlways()).isTrue();
			assertThat(UamTargetColumns.load(connection, "uam", "type_probe_always").get("always_id").identityAlways())
					.isTrue();
		}

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("kind", "Point");
		canonicalConnector.load(probeContext(), List.of(canonical("TypeProbe", "PROBE-1", attributes(
				"code", "AUS",
				"quantity", "7",
				"whole", "9000000000",
				"ratio", "1.5",
				"active", "yes",
				"external_id", "11111111-1111-1111-1111-111111111111",
				"observed_on", "2020-01-02",
				"updated_at", "2020-01-02T03:04:05",
				"payload", payload,
				"note", "Main",
				"status", "   ",
				"seq_id", " ",
				"legacy_seq", "",
				"search_text", "ignored"), List.of())));
		assertThat(row("""
				SELECT trim(code), quantity::text, whole::text, ratio::text, active::text,
				       external_id::text, observed_on::text, to_char(updated_at, 'YYYY-MM-DD HH24:MI:SS'),
				       payload->>'kind', status, seq_id::text, legacy_seq::text, search_text
				FROM uam.type_probe WHERE id = 'PROBE-1'
				""")).containsExactly(
				"AUS", "7", "9000000000", "1.5", "true",
				"11111111-1111-1111-1111-111111111111", "2020-01-02", "2020-01-02 03:04:05", "Point",
				"PLANNED", "1", "1", "Main");

		canonicalConnector.load(probeContext(), List.of(canonical("TypeProbe", "PROBE-2", Map.of(
				"note", "Second",
				"seq_id", "40",
				"legacy_seq", "8"), List.of())));
		assertThat(row("""
				SELECT seq_id::text, legacy_seq::text
				FROM uam.type_probe WHERE id = 'PROBE-2'
				""")).containsExactly("40", "8");

		assertThatThrownBy(() -> canonicalConnector.load(probeContext(), List.of(canonical(
				"TypeProbeAlways",
				"PROBE-3",
				Map.of("note", "Rejected", "always_id", "5"),
				List.of()))))
				.isInstanceOf(TargetInfrastructureException.class)
				.hasMessageContaining("always_id")
				.hasMessageContaining("generated by the database");
		assertThat(count("uam.type_probe")).isEqualTo(2);
		assertThat(count("uam.type_probe_always")).isZero();
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

	@Test
	void definitionConfigurationRejectsDerivedFields() throws Exception {
		String body = configuration("manual/rejected.csv", promotionConfiguration()).replace(
				"\"promotion\": {\"enabled\": true}",
				"""
				"tables": {"AddressLocation": "location"},
				"promotion": {"enabled": true}""");
		mockMvc.perform(post("/api/v1/migration-configurations")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
						.value(org.hamcrest.Matchers.containsString("definition.configuration.tables")));
	}

	@Test
	void singleRecordSftpCsvStagesAndPromotesFromEntityMappings() throws Exception {
		Path root = Files.createTempDirectory("sftp-single-record");
		Path knownHosts = Files.createTempFile("sftp-single-known-hosts", "");
		SshServer server = null;
		Path previousKnownHosts = sftpProperties.getKnownHostsFile();
		Map<String, SftpCredentialProperties.Credential> previousCredentials =
				new LinkedHashMap<>(sftpProperties.getCredentials());
		String password = UUID.randomUUID().toString();
		try {
			Path upload = root.resolve("upload");
			Files.createDirectories(upload);
			Files.writeString(upload.resolve("geographic_addresses_19102026_v1_single_record.csv"), singleRecordCsv());
			server = SshServer.setUpDefaultServer();
			server.setPort(0);
			SimpleGeneratorHostKeyProvider hostKeys = new SimpleGeneratorHostKeyProvider(root.resolve("host.ser"));
			hostKeys.setAlgorithm("EC");
			hostKeys.setKeySize(256);
			server.setKeyPairProvider(hostKeys);
			server.setPasswordAuthenticator((username, supplied, session) ->
					"e2e".equals(username) && password.equals(supplied));
			server.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
			server.setFileSystemFactory(new VirtualFileSystemFactory(root));
			server.start();
			PublicKey hostKey = server.getKeyPairProvider().loadKeys(null).iterator().next().getPublic();
			Files.writeString(knownHosts, "[127.0.0.1]:" + server.getPort() + " "
					+ PublicKeyEntry.toString(hostKey) + System.lineSeparator());
			sftpProperties.setKnownHostsFile(knownHosts);
			SftpCredentialProperties.Credential credential = new SftpCredentialProperties.Credential();
			credential.setUsername("e2e");
			credential.setPassword(password);
			Map<String, SftpCredentialProperties.Credential> credentials = new LinkedHashMap<>(previousCredentials);
			credentials.put("single-record", credential);
			sftpProperties.setCredentials(credentials);

			UUID runId = executeBody(singleRecordConfiguration(server.getPort()), 5);
			assertThat(count("uam.location")).isZero();
			assertThat(text(
					"SELECT COUNT(*) FROM migration_staging.uam_entity_stage WHERE migration_run_id = ?",
					runId)).isEqualTo("5");
			assertThat(stagedPayloads(runId).stream()
					.filter(payload -> "GeographicSubAddress".equals(payload.get("entity")))
					.count()).isZero();
			assertThat(stagedEntity(stagedPayloads(runId), "GeographicAddress", "SRC-SFU-000001")
					.get("targetEntity")).isEqualTo("geographic_address");

			promote(runId);
			assertThat(count("uam.location")).isEqualTo(2);
			assertThat(count("uam.geographic_address")).isEqualTo(1);
			assertThat(count("uam.geographic_location")).isEqualTo(1);
			assertThat(count("uam.location_geometry")).isEqualTo(1);
			assertThat(count("uam.geographic_sub_address")).isZero();
			assertThat(text("SELECT street_name FROM uam.geographic_address")).isEqualTo("Maple");
			assertThat(text("SELECT state_or_province FROM uam.geographic_address")).isEqualTo("TX");
			assertThat(text("SELECT city FROM uam.geographic_address")).isEqualTo("Austin");
			assertThat(text("""
					SELECT to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS')
					FROM uam.location WHERE id LIKE 'LOC-%'
					""")).isEqualTo("2024-01-01 00:00:37");
			assertThat(text("SELECT location_id FROM uam.geographic_address")).startsWith("LOC-");
			assertThat(text("SELECT geographic_location_id FROM uam.geographic_address")).startsWith("GEO-");
			assertThat(text("SELECT location_id FROM uam.geographic_location")).startsWith("GEO-LOC-");
			assertThat(text("SELECT location_id FROM uam.location_geometry")).startsWith("GEO-");
			assertThat(text("SELECT x::text FROM uam.location_geometry")).isEqualTo("-123.6429");
			assertThat(text("SELECT y::text FROM uam.location_geometry")).isEqualTo("25.7919");
		} finally {
			sftpProperties.setKnownHostsFile(previousKnownHosts);
			sftpProperties.setCredentials(previousCredentials);
			if (server != null && server.isStarted()) {
				server.stop();
			}
		}
	}

	@Test
	void serviceRecordsPromoteWithoutGeographicTables() throws Exception {
		execute("DROP SCHEMA IF EXISTS svc CASCADE");
		execute("CREATE SCHEMA svc");
		execute("""
				CREATE TABLE svc.service (
				  id varchar(64) PRIMARY KEY,
				  name varchar(128) NOT NULL,
				  created_at timestamptz NOT NULL
				)
				""");
		execute("""
				CREATE TABLE svc.service_characteristic (
				  id varchar(64) PRIMARY KEY,
				  service_id varchar(64) NOT NULL REFERENCES svc.service (id),
				  name varchar(128) NOT NULL,
				  value varchar(128)
				)
				""");
		String objectKey = "manual/service-" + System.nanoTime() + ".csv";
		write(objectKey, """
				service_id,service_name,characteristic_id,characteristic_name,characteristic_value
				SVC-100,Internet,CHR-1,speed,100
				""");
		try {
			UUID runId = executeBody(serviceConfiguration(objectKey), 2);
			assertThat(count("uam.location")).isZero();
			assertThat(count("svc.service")).isZero();
			assertThat(text(
					"SELECT COUNT(*) FROM migration_staging.uam_entity_stage WHERE migration_run_id = ?",
					runId)).isEqualTo("2");
			promote(runId);
			assertThat(count("uam.location")).isZero();
			assertThat(count("uam.geographic_address")).isZero();
			assertThat(count("svc.service")).isEqualTo(1);
			assertThat(count("svc.service_characteristic")).isEqualTo(1);
			assertThat(text("SELECT name FROM svc.service")).isEqualTo("Internet");
			assertThat(text("SELECT name FROM svc.service_characteristic")).isEqualTo("speed");
			assertThat(text("SELECT value FROM svc.service_characteristic")).isEqualTo("100");
			assertThat(text("""
					SELECT CASE WHEN c.service_id = s.id THEN 'match' ELSE 'mismatch' END
					FROM svc.service_characteristic c
					JOIN svc.service s ON s.id = c.service_id
					""")).isEqualTo("match");
			assertThat(text("SELECT CASE WHEN created_at IS NOT NULL THEN 'set' ELSE 'missing' END FROM svc.service"))
					.isEqualTo("set");
		} finally {
			execute("DROP SCHEMA IF EXISTS svc CASCADE");
		}
	}

	@Test
	void customerAndServiceUseSeparateConnectionsSchemasAndSourceFields() throws Exception {
		String suffix = Long.toUnsignedString(System.nanoTime());
		String customerDatabase = "cust_" + suffix;
		String serviceDatabase = "svc_" + suffix;
		String customerStage = "stg_c_" + suffix;
		String serviceStage = "stg_s_" + suffix;
		String baseUrl = environment.getProperty("etp.migration.target.postgresql.url");
		String username = environment.getProperty("etp.migration.target.postgresql.username");
		String password = environment.getProperty("etp.migration.target.postgresql.password");
		String customerUrl = baseUrl.replaceFirst("^(jdbc:postgresql://[^/]+/)[^?]+", "$1" + customerDatabase);
		String serviceUrl = baseUrl.replaceFirst("^(jdbc:postgresql://[^/]+/)[^?]+", "$1" + serviceDatabase);
		createDatabase(customerDatabase);
		createDatabase(serviceDatabase);
		executeJdbc(customerUrl, username, password, """
				CREATE SCHEMA customer;
				CREATE TABLE customer.customer (
				  id varchar(64) PRIMARY KEY,
				  name varchar(128) NOT NULL,
				  created_at timestamptz NOT NULL
				);
				CREATE TABLE customer.customer_contact (
				  id varchar(64) PRIMARY KEY,
				  customer_id varchar(64) NOT NULL REFERENCES customer.customer (id),
				  email varchar(128) NOT NULL
				)
				""");
		executeJdbc(serviceUrl, username, password, """
				CREATE SCHEMA "serviceInventory";
				CREATE TABLE "serviceInventory".service (
				  id varchar(64) PRIMARY KEY,
				  name varchar(128) NOT NULL,
				  created_at timestamptz NOT NULL
				);
				CREATE TABLE "serviceInventory".service_characteristic (
				  id varchar(64) PRIMARY KEY,
				  service_id varchar(64) NOT NULL REFERENCES "serviceInventory".service (id),
				  name varchar(128) NOT NULL
				)
				""");
		registerConnection("migration-db", baseUrl, username, password);
		registerConnection("customer-db", customerUrl, username, password);
		registerConnection("service-db", serviceUrl, username, password);
		execute("CREATE SCHEMA " + customerStage);
		execute("CREATE SCHEMA " + serviceStage);
		execute(stagingTable(customerStage));
		execute(stagingTable(serviceStage));

		assertThatThrownBy(() -> connectionRegistry.target(Map.of("target", Map.of("connectionRef", "missing-db"))))
				.isInstanceOf(com.etp.migration.connector.target.TargetInfrastructureException.class)
				.hasMessageContaining("missing-db")
				.hasMessageNotContaining(password);

		String customerKey = "manual/customer-" + suffix + ".csv";
		write(customerKey, """
				party_id,display_name,contact_email
				P-1,Ada,ada@example.com
				""");
		UUID customerRun = executeBody(relationalConfiguration(
				"customer",
				"Customer",
				"customer",
				"customer-db",
				customerStage,
				customerKey,
				"""
				{"entity": "Customer", "targetEntity": "customer", "sourceKey": "party_id", "targetKey": "id", "targetIdentity": {"strategy": "PREFIX_SEQUENCE", "prefix": "CU-", "sequenceLength": 6}},
				{"entity": "CustomerContact", "targetEntity": "customer_contact", "sourceKey": "contact_email", "targetKey": "id", "targetIdentity": {"strategy": "PREFIX_SEQUENCE", "prefix": "CT-", "sequenceLength": 6}}
				""",
				"""
				{"parent": "Customer", "child": "CustomerContact", "cardinality": "ONE_TO_MANY", "keys": [{"parentField": "party_id", "childField": "party_id"}], "targetForeignKey": {"column": "customer_id", "referencesColumn": "id"}}
				""",
				"""
				{"sequenceNo": 1, "entity": "Customer", "sourceField": "display_name", "targetField": "name", "transformationType": "DIRECT", "mandatory": true, "enabled": true},
				{"sequenceNo": 2, "entity": "CustomerContact", "sourceField": "contact_email", "targetField": "email", "transformationType": "DIRECT", "mandatory": true, "enabled": true}
				"""), 2);
		assertThat(text("SELECT COUNT(*) FROM " + customerStage + ".entity_stage WHERE migration_run_id = ?", customerRun))
				.isEqualTo("2");
		assertThat(text("SELECT COUNT(*) FROM " + serviceStage + ".entity_stage WHERE migration_run_id = ?", customerRun))
				.isEqualTo("0");
		promote(customerRun);
		assertThat(scalar(customerUrl, username, password, "SELECT name FROM customer.customer")).isEqualTo("Ada");
		assertThat(scalar(customerUrl, username, password, "SELECT email FROM customer.customer_contact")).isEqualTo("ada@example.com");
		assertThat(scalar(customerUrl, username, password, """
				SELECT CASE WHEN c.customer_id = p.id THEN 'match' ELSE 'mismatch' END
				FROM customer.customer_contact c JOIN customer.customer p ON p.id = c.customer_id
				""")).isEqualTo("match");
		assertThat(scalar(serviceUrl, username, password, "SELECT COUNT(*) FROM \"serviceInventory\".service")).isEqualTo("0");

		String serviceKey = "manual/service-" + suffix + ".csv";
		write(serviceKey, """
				svc_code,svc_name,char_code,char_name
				S-1,Fiber,C-1,bandwidth
				""");
		UUID serviceRun = executeBody(relationalConfiguration(
				"service",
				"Service",
				"serviceInventory",
				"service-db",
				serviceStage,
				serviceKey,
				"""
				{"entity": "Service", "targetEntity": "service", "sourceKey": "svc_code", "targetKey": "id", "targetIdentity": {"strategy": "PREFIX_SEQUENCE", "prefix": "SV-", "sequenceLength": 6}},
				{"entity": "ServiceCharacteristic", "targetEntity": "service_characteristic", "sourceKey": "char_code", "targetKey": "id", "targetIdentity": {"strategy": "PREFIX_SEQUENCE", "prefix": "SC-", "sequenceLength": 6}}
				""",
				"""
				{"parent": "Service", "child": "ServiceCharacteristic", "cardinality": "ONE_TO_MANY", "keys": [{"parentField": "svc_code", "childField": "svc_code"}], "targetForeignKey": {"column": "service_id", "referencesColumn": "id"}}
				""",
				"""
				{"sequenceNo": 1, "entity": "Service", "sourceField": "svc_name", "targetField": "name", "transformationType": "DIRECT", "mandatory": true, "enabled": true},
				{"sequenceNo": 2, "entity": "ServiceCharacteristic", "sourceField": "char_name", "targetField": "name", "transformationType": "DIRECT", "mandatory": true, "enabled": true}
				"""), 2);
		assertThat(text("SELECT COUNT(*) FROM " + serviceStage + ".entity_stage WHERE migration_run_id = ?", serviceRun))
				.isEqualTo("2");
		assertThat(text("SELECT COUNT(*) FROM " + customerStage + ".entity_stage WHERE migration_run_id = ?", serviceRun))
				.isEqualTo("0");
		promote(serviceRun);
		assertThat(scalar(serviceUrl, username, password, "SELECT name FROM \"serviceInventory\".service")).isEqualTo("Fiber");
		assertThat(scalar(serviceUrl, username, password, "SELECT name FROM \"serviceInventory\".service_characteristic"))
				.isEqualTo("bandwidth");
		assertThat(scalar(serviceUrl, username, password, """
				SELECT CASE WHEN c.service_id = s.id THEN 'match' ELSE 'mismatch' END
				FROM "serviceInventory".service_characteristic c
				JOIN "serviceInventory".service s ON s.id = c.service_id
				""")).isEqualTo("match");
		assertThat(scalar(customerUrl, username, password, "SELECT COUNT(*) FROM customer.customer")).isEqualTo("1");
		assertThat(count("uam.location")).isZero();
	}

	@Test
	void missingTargetTableAndMismatchedForeignKeyFailBeforeInsert() {
		assertThatThrownBy(() -> canonicalConnector.load(context("uam"), List.of(record(
				"AddressLocation",
				"missing_table",
				"LOC-1",
				Map.of("at_type", "geographicAddress"),
				List.of()))))
				.isInstanceOf(TargetInfrastructureException.class)
				.hasMessageContaining("uam.missing_table")
				.hasMessageContaining("was not found");

		assertThatThrownBy(() -> canonicalConnector.load(context("uam"), List.of(
				canonical("GeographicLocation", "GEO-1", Map.of(
						"geo_json_type", "Point",
						"location_id", "GEO-LOC-1"), List.of()),
				canonical("GeographicAddress", "ADDR-1", Map.of(), List.of(
						link("GeographicLocation", "GEO-1", "geographic_location_id", "location_id"))))))
				.isInstanceOf(TargetInfrastructureException.class)
				.hasMessageContaining("geographic_location_id")
				.hasMessageContaining("references 'id'")
				.hasMessageContaining("not 'location_id'");

		assertThatThrownBy(() -> canonicalConnector.load(context("uam"), List.of(
				canonical("AddressLocation", "LOC-1", Map.of(), List.of()))))
				.isInstanceOf(TargetInfrastructureException.class)
				.hasMessage("Column 'at_type' on 'location' is required");
	}

	private static LoadableTargetRecord record(
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
		return new LoadableTargetRecord(1L, new TargetRecord(fields), entity + ":" + targetIdentity);
	}

	private void promote(UUID runId) throws Exception {
		PhaseAwait.reconciled(mockMvc, runId);
		PhaseAwait.prepared(mockMvc, runId);
		PhaseAwait.promoted(mockMvc, runId);
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
		return executeBody(configuration(objectKey, promotionConfiguration()), expectedSuccess);
	}

	private UUID executeBody(String body, int expectedSuccess) throws Exception {
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
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("QUEUED"))
				.andReturn();
		String runId = com.jayway.jsonpath.JsonPath.read(executed.getResponse().getContentAsString(), "$.id");
		MvcResult finished = com.etp.migration.run.ExecutionAwait.untilTerminal(mockMvc, runId);
		String response = finished.getResponse().getContentAsString();
		assertThat(com.jayway.jsonpath.JsonPath.read(response, "$.status").toString())
				.as(response + " " + errors(UUID.fromString(runId)))
				.isEqualTo("COMPLETED");
		assertThat(((Number) com.jayway.jsonpath.JsonPath.read(response, "$.successRecords")).intValue())
				.as(response)
				.isEqualTo(expectedSuccess);
		return UUID.fromString(runId);
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
				        {"parent": "AddressLocation", "child": "GeographicAddress", "cardinality": "ONE_TO_ONE", "keys": [{"parentField": "address_id", "childField": "address_id"}], "targetForeignKey": {"column": "location_id", "referencesColumn": "id"}},
				        {"parent": "AddressLocation", "child": "GeographicPointLocation", "cardinality": "ONE_TO_ONE", "keys": [{"parentField": "geo_id", "childField": "geo_id"}]},
				        {"parent": "GeographicPointLocation", "child": "GeographicLocation", "cardinality": "ONE_TO_ONE", "keys": [{"parentField": "geo_id", "childField": "geo_id"}], "targetForeignKey": {"column": "location_id", "referencesColumn": "id"}},
				        {"parent": "GeographicPointLocation", "child": "LocationGeometry", "cardinality": "ONE_TO_ONE", "keys": [{"parentField": "geo_id", "childField": "geo_id"}]},
				        {"parent": "GeographicLocation", "child": "GeographicAddress", "cardinality": "ONE_TO_ONE", "keys": [{"parentField": "geo_id", "childField": "geo_id"}], "targetForeignKey": {"column": "geographic_location_id", "referencesColumn": "id"}},
				        {"parent": "GeographicLocation", "child": "LocationGeometry", "cardinality": "ONE_TO_ONE", "keys": [{"parentField": "geo_id", "childField": "geo_id"}], "targetForeignKey": {"column": "location_id", "referencesColumn": "id"}},
				        {"parent": "GeographicAddress", "child": "GeographicSubAddress", "cardinality": "ONE_TO_MANY", "keys": [{"parentField": "address_id", "childField": "address_id"}], "targetForeignKey": {"column": "address_id", "referencesColumn": "id"}},
				        {"parent": "GeographicLocation", "child": "GeographicSubAddress", "cardinality": "ONE_TO_MANY", "keys": [{"parentField": "geo_id", "childField": "geo_id"}], "targetForeignKey": {"column": "geographic_location_id", "referencesColumn": "id"}}
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

	private MigrationTargetContext contextWithoutColumnTypes() {
		Map<String, Object> configuration = new LinkedHashMap<>();
		configuration.put("target", Map.of("mode", "UAM", "schema", "uam"));
		return new MigrationTargetContext(
				UUID.randomUUID(),
				1L,
				1,
				UamPostgreSqlTargetConnector.TARGET_TYPE,
				"location",
				LoadMode.BULK,
				configuration);
	}

	private MigrationTargetContext probeContext() {
		Map<String, Object> configuration = new LinkedHashMap<>();
		configuration.put("target", Map.of("mode", "UAM", "schema", "uam"));
		return new MigrationTargetContext(
				UUID.randomUUID(),
				1L,
				1,
				UamPostgreSqlTargetConnector.TARGET_TYPE,
				"type_probe",
				LoadMode.BULK,
				configuration);
	}

	private static Map<String, Object> attributes(Object... pairs) {
		Map<String, Object> values = new LinkedHashMap<>();
		for (int index = 0; index < pairs.length; index += 2) {
			values.put(String.valueOf(pairs[index]), pairs[index + 1]);
		}
		return values;
	}

	private MigrationTargetContext context(String schema) {
		return new MigrationTargetContext(
				UUID.randomUUID(),
				1L,
				1,
				UamPostgreSqlTargetConnector.TARGET_TYPE,
				"location",
				LoadMode.BULK,
				Map.of("target", Map.of("mode", "UAM", "schema", schema)));
	}

	private static LoadableTargetRecord canonical(
			String entity,
			String targetIdentity,
			Map<String, Object> attributes,
			List<Map<String, Object>> relationships) {
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("entity", entity);
		String table = TARGET_TABLES.get(entity);
		if (table == null) {
			throw new IllegalArgumentException("No target table for " + entity);
		}
		fields.put("targetEntity", table);
		fields.put("targetKey", "id");
		fields.put("targetIdentity", targetIdentity);
		fields.put("attributes", new LinkedHashMap<>(attributes));
		fields.put("relationships", relationships);
		return new LoadableTargetRecord(1L, new TargetRecord(fields), entity + ":" + targetIdentity);
	}

	private static final Map<String, String> TARGET_TABLES = Map.of(
			"AddressLocation", "location",
			"GeographicPointLocation", "location",
			"GeographicAddress", "geographic_address",
			"GeographicLocation", "geographic_location",
			"LocationGeometry", "location_geometry",
			"GeographicSubAddress", "geographic_sub_address",
			"TypeProbe", "type_probe",
			"TypeProbeAlways", "type_probe_always");

	private static Map<String, Object> link(
			String entity,
			String targetIdentity,
			String column,
			String referencesColumn) {
		Map<String, Object> parent = new LinkedHashMap<>();
		parent.put("parentEntity", entity);
		parent.put("parentTargetIdentity", targetIdentity);
		parent.put("targetForeignKey", Map.of("column", column, "referencesColumn", referencesColumn));
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

	private static String singleRecordCsv() {
		return """
				source_address_id,source_parent_address_id,source_sub_address_id,premise_type,market_segment,street_number,street_name,street_type,building_name,unit_number,private_street_number,private_street_name,city,state_or_province,postcode,country,latitude,longitude,address_type,validation_status,created_at
				SRC-SFU-000001,,,SFU,RESIDENTIAL,38,Maple,St,,,,,Austin,TX,78701,US,25.7919,-123.6429,RESIDENTIAL,VALIDATED,2024-01-01T00:00:37Z
				""";
	}

	private static String singleRecordConfiguration(int port) {
		String name = "sftp-single-" + System.nanoTime();
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
				    "configuration": {
				      "target": {
				        "mode": "UAM",
				        "schema": "uam",
				        "stagingSchema": "migration_staging",
				        "stagingTable": "uam_entity_stage",
				        "mergeStrategy": "NONE"
				      },
				      "promotion": {"enabled": true}
				    }
				  },
				  "entityMappings": {
				    "name": "%s-mapping",
				    "version": "1",
				    "sourceType": "FILE",
				    "targetType": "UAM_POSTGRESQL",
				    "configuration": {
				      "modelType": "ENTITY_RELATIONSHIP",
				      "rootEntity": "AddressLocation",
				      "entities": [
				        {"entity": "AddressLocation", "targetEntity": "location", "sourceKey": "source_address_id", "targetKey": "id", "targetIdentity": {"strategy": "PREFIX_SEQUENCE", "prefix": "LOC-", "sequenceLength": 9}},
				        {"entity": "GeographicPointLocation", "targetEntity": "location", "sourceKey": "source_address_id", "targetKey": "id", "targetIdentity": {"strategy": "PREFIX_SEQUENCE", "prefix": "GEO-LOC-", "sequenceLength": 9}},
				        {"entity": "GeographicAddress", "targetEntity": "geographic_address", "sourceKey": "source_address_id", "targetKey": "id", "targetIdentity": {"strategy": "PREFIX_SEQUENCE", "prefix": "ADDR-", "sequenceLength": 10}},
				        {"entity": "GeographicLocation", "targetEntity": "geographic_location", "sourceKey": "source_address_id", "targetKey": "id", "targetIdentity": {"strategy": "PREFIX_SEQUENCE", "prefix": "GEO-", "sequenceLength": 9}},
				        {"entity": "LocationGeometry", "targetEntity": "location_geometry", "sourceKey": "source_address_id", "targetKey": "id", "targetIdentity": {"strategy": "PREFIX_SEQUENCE", "prefix": "GEOM-", "sequenceLength": 9}},
				        {"entity": "GeographicSubAddress", "targetEntity": "geographic_sub_address", "sourceKey": "source_sub_address_id", "targetKey": "id", "targetIdentity": {"strategy": "PREFIX_SEQUENCE", "prefix": "SUB-", "sequenceLength": 9}}
				      ],
				      "relationships": [
				        {"parent": "AddressLocation", "child": "GeographicAddress", "cardinality": "ONE_TO_ONE", "keys": [{"parentField": "source_address_id", "childField": "source_address_id"}], "targetForeignKey": {"column": "location_id", "referencesColumn": "id"}},
				        {"parent": "AddressLocation", "child": "GeographicPointLocation", "cardinality": "ONE_TO_ONE", "keys": [{"parentField": "source_address_id", "childField": "source_address_id"}]},
				        {"parent": "GeographicPointLocation", "child": "GeographicLocation", "cardinality": "ONE_TO_ONE", "keys": [{"parentField": "source_address_id", "childField": "source_address_id"}], "targetForeignKey": {"column": "location_id", "referencesColumn": "id"}},
				        {"parent": "GeographicPointLocation", "child": "LocationGeometry", "cardinality": "ONE_TO_ONE", "keys": [{"parentField": "source_address_id", "childField": "source_address_id"}]},
				        {"parent": "GeographicLocation", "child": "GeographicAddress", "cardinality": "ONE_TO_ONE", "keys": [{"parentField": "source_address_id", "childField": "source_address_id"}], "targetForeignKey": {"column": "geographic_location_id", "referencesColumn": "id"}},
				        {"parent": "GeographicLocation", "child": "LocationGeometry", "cardinality": "ONE_TO_ONE", "keys": [{"parentField": "source_address_id", "childField": "source_address_id"}], "targetForeignKey": {"column": "location_id", "referencesColumn": "id"}},
				        {"parent": "GeographicAddress", "child": "GeographicSubAddress", "cardinality": "ONE_TO_MANY", "keys": [{"parentField": "source_address_id", "childField": "source_address_id"}], "targetForeignKey": {"column": "address_id", "referencesColumn": "id"}},
				        {"parent": "GeographicLocation", "child": "GeographicSubAddress", "cardinality": "ONE_TO_MANY", "keys": [{"parentField": "source_address_id", "childField": "source_address_id"}], "targetForeignKey": {"column": "geographic_location_id", "referencesColumn": "id"}}
				      ]
				    }
				  },
				  "fieldMappings": [
				    {"sequenceNo": 1, "entity": "AddressLocation", "targetField": "at_type", "transformationType": "DEFAULT", "defaultValue": "geographicAddress", "mandatory": true, "enabled": true},
				    {"sequenceNo": 2, "entity": "AddressLocation", "sourceField": "created_at", "targetField": "created_at", "transformationType": "DIRECT", "enabled": true},
				    {"sequenceNo": 3, "entity": "GeographicPointLocation", "targetField": "at_type", "transformationType": "DEFAULT", "defaultValue": "geographicLocation", "mandatory": true, "enabled": true},
				    {"sequenceNo": 4, "entity": "GeographicPointLocation", "sourceField": "created_at", "targetField": "created_at", "transformationType": "DIRECT", "enabled": true},
				    {"sequenceNo": 5, "entity": "GeographicAddress", "sourceField": "street_number", "targetField": "street_number", "transformationType": "DIRECT", "enabled": true},
				    {"sequenceNo": 6, "entity": "GeographicAddress", "sourceField": "street_name", "targetField": "street_name", "transformationType": "DIRECT", "enabled": true},
				    {"sequenceNo": 7, "entity": "GeographicAddress", "sourceField": "street_type", "targetField": "street_type", "transformationType": "DIRECT", "enabled": true},
				    {"sequenceNo": 8, "entity": "GeographicAddress", "sourceField": "city", "targetField": "city", "transformationType": "DIRECT", "enabled": true},
				    {"sequenceNo": 9, "entity": "GeographicAddress", "sourceField": "state_or_province", "targetField": "state_or_province", "transformationType": "DIRECT", "enabled": true},
				    {"sequenceNo": 10, "entity": "GeographicAddress", "sourceField": "postcode", "targetField": "postcode", "transformationType": "DIRECT", "enabled": true},
				    {"sequenceNo": 11, "entity": "GeographicAddress", "sourceField": "country", "targetField": "country", "transformationType": "DIRECT", "enabled": true},
				    {"sequenceNo": 12, "entity": "GeographicAddress", "sourceField": "address_type", "targetField": "address_type", "transformationType": "DIRECT", "enabled": true},
				    {"sequenceNo": 13, "entity": "GeographicAddress", "sourceField": "validation_status", "targetField": "validation_status", "transformationType": "DIRECT", "enabled": true},
				    {"sequenceNo": 14, "entity": "GeographicLocation", "targetField": "geo_json_type", "transformationType": "DEFAULT", "defaultValue": "Point", "mandatory": true, "enabled": true},
				    {"sequenceNo": 15, "entity": "LocationGeometry", "targetField": "geometry_type", "transformationType": "DEFAULT", "defaultValue": "Point", "mandatory": true, "enabled": true},
				    {"sequenceNo": 16, "entity": "LocationGeometry", "sourceField": "longitude", "targetField": "x", "transformationType": "DIRECT", "enabled": true},
				    {"sequenceNo": 17, "entity": "LocationGeometry", "sourceField": "latitude", "targetField": "y", "transformationType": "DIRECT", "enabled": true},
				    {"sequenceNo": 18, "entity": "GeographicSubAddress", "sourceField": "building_name", "targetField": "building_name", "transformationType": "DIRECT", "enabled": true},
				    {"sequenceNo": 19, "entity": "GeographicSubAddress", "sourceField": "unit_number", "targetField": "sub_unit_number", "transformationType": "DIRECT", "enabled": true},
				    {"sequenceNo": 20, "entity": "GeographicSubAddress", "sourceField": "private_street_number", "targetField": "private_street_number", "transformationType": "DIRECT", "enabled": true},
				    {"sequenceNo": 21, "entity": "GeographicSubAddress", "sourceField": "private_street_name", "targetField": "private_street_name", "transformationType": "DIRECT", "enabled": true}
				  ],
				  "source": {
				    "name": "%s-source",
				    "sourceType": "FILE",
				    "enabled": true,
				    "configuration": {
				      "provider": "SFTP",
				      "fileFormat": "CSV",
				      "host": "127.0.0.1",
				      "port": %d,
				      "credentialRef": "single-record",
				      "remotePath": "/upload/geographic_addresses_19102026_v1_single_record.csv",
				      "hasHeader": true,
				      "delimiter": ","
				    }
				  }
				}
				""".formatted(name, name, name, port);
	}

	private static String serviceConfiguration(String objectKey) {
		String name = "service-" + System.nanoTime();
		return """
				{
				  "definition": {
				    "name": "%s",
				    "version": "1",
				    "sourceType": "FILE",
				    "targetType": "POSTGRESQL",
				    "targetEntity": "service",
				    "batchSize": 1000,
				    "loadMode": "BULK",
				    "configuration": {
				      "target": {
				        "mode": "UAM",
				        "schema": "svc",
				        "stagingSchema": "migration_staging",
				        "stagingTable": "uam_entity_stage",
				        "mergeStrategy": "NONE"
				      },
				      "promotion": {"enabled": true}
				    }
				  },
				  "entityMappings": {
				    "name": "%s-mapping",
				    "version": "1",
				    "sourceType": "FILE",
				    "targetType": "UAM_POSTGRESQL",
				    "configuration": {
				      "modelType": "ENTITY_RELATIONSHIP",
				      "rootEntity": "Service",
				      "entities": [
				        {"entity": "Service", "targetEntity": "service", "sourceKey": "service_id", "targetKey": "id", "targetIdentity": {"strategy": "PREFIX_SEQUENCE", "prefix": "SV-", "sequenceLength": 6}},
				        {"entity": "ServiceCharacteristic", "targetEntity": "service_characteristic", "sourceKey": "characteristic_id", "targetKey": "id", "targetIdentity": {"strategy": "PREFIX_SEQUENCE", "prefix": "CH-", "sequenceLength": 6}}
				      ],
				      "relationships": [
				        {"parent": "Service", "child": "ServiceCharacteristic", "cardinality": "ONE_TO_MANY", "keys": [{"parentField": "service_id", "childField": "service_id"}], "targetForeignKey": {"column": "service_id", "referencesColumn": "id"}}
				      ]
				    }
				  },
				  "fieldMappings": [
				    {"sequenceNo": 1, "entity": "Service", "sourceField": "service_name", "targetField": "name", "transformationType": "DIRECT", "mandatory": true, "enabled": true},
				    {"sequenceNo": 2, "entity": "ServiceCharacteristic", "sourceField": "characteristic_name", "targetField": "name", "transformationType": "DIRECT", "mandatory": true, "enabled": true},
				    {"sequenceNo": 3, "entity": "ServiceCharacteristic", "sourceField": "characteristic_value", "targetField": "value", "transformationType": "DIRECT", "enabled": true}
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
				""".formatted(name, name, name, objectKey);
	}

	private void createDatabase(String name) throws Exception {
		try (Connection connection = targetDataSource.getConnection()) {
			connection.setAutoCommit(true);
			try (Statement statement = connection.createStatement()) {
				statement.execute("CREATE DATABASE " + name);
			}
		}
	}

	private void executeJdbc(String url, String username, String password, String sql) throws Exception {
		try (Connection connection = DriverManager.getConnection(url, username, password);
				Statement statement = connection.createStatement()) {
			for (String command : sql.split(";")) {
				if (!command.isBlank()) {
					statement.execute(command);
				}
			}
		}
	}

	private String scalar(String url, String username, String password, String sql) throws Exception {
		try (Connection connection = DriverManager.getConnection(url, username, password);
				ResultSet resultSet = connection.createStatement().executeQuery(sql)) {
			resultSet.next();
			return resultSet.getString(1);
		}
	}

	private void registerConnection(String name, String url, String username, String password) {
		PostgreSqlConnectionProperties.Profile profile = new PostgreSqlConnectionProperties.Profile();
		profile.setUrl(url);
		profile.setUsername(username);
		profile.setPassword(password);
		profile.setMaxPoolSize(2);
		connectionProperties.getConnections().put(name, profile);
	}

	private static String stagingTable(String schema) {
		return """
				CREATE TABLE %s.entity_stage (
				  migration_run_id UUID NOT NULL,
				  migration_batch_id BIGINT NOT NULL,
				  source_record_number BIGINT NOT NULL,
				  target_payload JSONB NOT NULL,
				  idempotency_key VARCHAR(500),
				  loaded_at TIMESTAMPTZ NOT NULL
				)
				""".formatted(schema);
	}

	private static String relationalConfiguration(
			String targetEntity,
			String root,
			String schema,
			String connectionRef,
			String stagingSchema,
			String objectKey,
			String entities,
			String relationships,
			String rules) {
		String name = root.toLowerCase(Locale.ROOT) + "-" + System.nanoTime();
		return """
				{
				  "definition": {
				    "name": "%s",
				    "version": "1",
				    "sourceType": "FILE",
				    "targetType": "POSTGRESQL",
				    "targetEntity": "%s",
				    "batchSize": 1000,
				    "loadMode": "BULK",
				    "configuration": {
				      "target": {
				        "mode": "POSTGRESQL",
				        "connectionRef": "%s",
				        "stagingConnectionRef": "migration-db",
				        "schema": "%s",
				        "stagingSchema": "%s",
				        "stagingTable": "entity_stage",
				        "mergeStrategy": "NONE"
				      },
				      "promotion": {"enabled": true}
				    }
				  },
				  "entityMappings": {
				    "name": "%s-mapping",
				    "version": "1",
				    "sourceType": "FILE",
				    "targetType": "POSTGRESQL",
				    "configuration": {
				      "modelType": "ENTITY_RELATIONSHIP",
				      "rootEntity": "%s",
				      "entities": [%s],
				      "relationships": [%s]
				    }
				  },
				  "fieldMappings": [%s],
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
				""".formatted(
				name, targetEntity, connectionRef, schema, stagingSchema,
				name, root, entities, relationships, rules, name, objectKey);
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
