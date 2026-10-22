package com.etp.migration.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.etp.migration.connector.source.csv.CsvSourceConnector;
import com.etp.migration.connector.source.csv.MultiFileEntitySource;
import com.etp.migration.connector.storage.DefaultArtifactStorage;
import com.etp.migration.connector.storage.FileSystemArtifactStorage;
import com.etp.migration.connector.target.LoadableTargetRecord;
import com.etp.migration.connector.target.MigrationTargetConnector;
import com.etp.migration.connector.target.MigrationTargetContext;
import com.etp.migration.connector.target.TargetLoadResult;
import com.etp.migration.connector.target.TargetRecordError;
import com.etp.migration.entity.LoadMode;
import com.etp.migration.entity.MigrationMappingRule;
import com.etp.migration.entity.MigrationStage;
import com.etp.migration.entity.TransformationType;
import com.etp.migration.extraction.EntityExtractor;
import com.etp.migration.extraction.EntityRecord;
import com.etp.migration.identity.EntityIdentity;
import com.etp.migration.identity.TargetIdentityLookup;
import com.etp.migration.mapping.EntityDefinition;
import com.etp.migration.mapping.EntityRelationshipMapping;
import com.etp.migration.mapping.RelationshipCardinality;
import com.etp.migration.mapping.RelationshipDefinition;
import com.etp.migration.mapping.RelationshipKey;
import com.etp.migration.mapping.TargetForeignKey;
import com.etp.migration.planning.ExecutionBatchPlan;
import com.etp.migration.planning.ExecutionPlanner;
import com.etp.migration.planning.ExecutionWorkItem;
import com.etp.migration.planning.WorkItemParent;
import com.etp.migration.processing.MigrationErrorCodes;
import com.etp.migration.relationship.RelationshipResolver;
import com.etp.migration.scope.LogicalMigrationScopeBuilder;
import com.etp.migration.transformation.MappingEngine;
import com.etp.migration.transformation.TransformationEngine;
import com.etp.migration.validation.ValidationEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EntityRelationshipExecutionTest {

	@TempDir
	Path tempDir;

	private EntityRelationshipPlanSource planSource;
	private EntityRelationshipBatchExecutor executor;
	private RecordingTarget target;

	@BeforeEach
	void setUp() {
		TransformationEngine transformations = new TransformationEngine();
		MappingEngine mappingEngine = new MappingEngine(transformations);
		EntityExtractor extractor = new EntityExtractor(mappingEngine);
		FileSystemArtifactStorage files = new FileSystemArtifactStorage(tempDir);
		files.initializeRoot();
		CsvSourceConnector csv = new CsvSourceConnector(new DefaultArtifactStorage(files));
		EntityCsvAccess csvAccess = new EntityCsvAccess(csv, extractor, new MultiFileEntitySource(csv, extractor));
		csvAccess.setFileStorage(files);
		planSource = new EntityRelationshipPlanSource(
				csvAccess,
				new RelationshipResolver(),
				new LogicalMigrationScopeBuilder(),
				new ExecutionPlanner());
		executor = new EntityRelationshipBatchExecutor(new EntityTargetComposer(mappingEngine, new ValidationEngine()));
		target = new RecordingTarget();
	}

	@Test
	void runsASingleFileEntityMigrationThroughStagingPayloads() {
		RunResult result = runRecords(placeModel(), placeRules(), placeRecords(), placeLookup(), 100);

		assertThat(result.plans()).hasSize(1);
		assertThat(result.plans().get(0).workItems()).extracting(ExecutionWorkItem::rootIdentity).containsOnly("L001");
		assertThat(loadedIdentities()).containsExactlyInAnyOrder(
				"Location:L001", "Address:A001", "Address:A002", "Site:S001", "Site:S002", "Site:S003");
		Map<String, Object> address = payload("Address", "A001");
		assertThat(address.get("targetIdentity")).isEqualTo("TA001");
		assertThat(address.get("targetEntity")).isEqualTo("geographic_address");
		assertThat(address.get("sourceKey")).isEqualTo("address_id");
		assertThat(address.get("targetKey")).isEqualTo("id");
		assertThat(attributes(address).get("name")).isEqualTo("One");
		assertThat(parentTargets(address)).containsExactly("TL001");
		assertThat(parentTargets(payload("Site", "S001"))).containsExactly("TA001");
		assertThat(parentTargets(payload("Site", "S003"))).containsExactly("TA002");
	}

	@Test
	void splitsWorkAcrossBatchesAndKeepsParentTargetIdentities() {
		RunResult result = runRecords(placeModel(), placeRules(), placeRecords(), placeLookup(), 2);

		assertThat(result.plans()).hasSizeGreaterThan(1);
		assertThat(result.plans().stream().mapToInt(plan -> plan.workItems().size()).sum()).isEqualTo(6);
		assertThat(loadedIdentities()).containsExactlyInAnyOrder(
				"Location:L001", "Address:A001", "Address:A002", "Site:S001", "Site:S002", "Site:S003");
		assertThat(target.loads.get(0).stream().map(EntityRelationshipExecutionTest::label).toList())
				.doesNotContain("Site:S001");
		assertThat(parentTargets(payload("Site", "S001"))).containsExactly("TA001");
		assertThat(parentTargets(payload("Site", "S003"))).containsExactly("TA002");
		List<String> order = result.plans().stream()
				.flatMap(plan -> plan.workItems().stream())
				.map(item -> item.entityName() + ":" + item.sourceIdentity())
				.toList();
		assertThat(order.indexOf("Location:L001")).isLessThan(order.indexOf("Address:A001"));
		assertThat(order.indexOf("Address:A001")).isLessThan(order.indexOf("Site:S001"));
	}

	@Test
	void resolvesAParentThatWasPlannedInAnEarlierBatch() {
		EntityRelationshipMapping mapping = placeModel();
		ExecutionBatchPlan first = new ExecutionBatchPlan(1, List.of(
				item("Location", "L001", 1, "L001"),
				item("Address", "A001", 2, "L001", parent("Location", "L001"))));
		ExecutionBatchPlan second = new ExecutionBatchPlan(2, List.of(
				item("Site", "S001", 3, "L001", parent("Address", "A001"))));
		MapLookup lookup = placeLookup();

		execute(mapping, placeRules(), first, records(placeRecords(), first), lookup, 100);
		execute(mapping, placeRules(), second, records(placeRecords(), second), lookup, 100);

		assertThat(target.loads).hasSize(2);
		assertThat(target.loads.get(1)).singleElement().satisfies(payload -> {
			assertThat(label(payload)).isEqualTo("Site:S001");
			assertThat(parentTargets(payload)).containsExactly("TA001");
		});
	}

	@Test
	void singleFileAndMultiFileProduceTheSameLogicalPayloads() throws Exception {
		write("single.csv", """
				location_id,location_name,address_id,address_name,address_location_id,site_id,site_name,site_address_id
				L001,Main,,,,,,
				,,A001,One,L001,,,
				,,A002,Two,L001,,,
				,,,,,S001,S1,A001
				,,,,,S002,S2,A001
				,,,,,S003,S3,A002
				""");
		write("location.csv", """
				location_id,location_name
				L001,Main
				""");
		write("address.csv", """
				address_id,address_name,address_location_id
				A001,One,L001
				A002,Two,L001
				""");
		write("site.csv", """
				site_id,site_name,site_address_id
				S001,S1,A001
				S002,S2,A001
				S003,S3,A002
				""");
		RecordingTarget singleTarget = target;
		runCsv(singleConfig("single.csv"), placeModel(), placeRules(), placeLookup(), 100);
		List<String> single = signatures();
		target = new RecordingTarget();
		runCsv(multiConfig(), placeModel(), placeRules(), placeLookup(), 100);

		assertThat(signatures()).containsExactlyInAnyOrderElementsOf(single);
		assertThat(singleTarget.loads).isNotEmpty();
	}

	@Test
	void keepsEveryParentTargetIdentity() {
		EntityRelationshipMapping mapping = mapping(
				"Alpha",
				List.of(
						def("Alpha", "alpha_id", "alpha"),
						def("Beta", "beta_id", "beta"),
						def("Gamma", "gamma_id", "gamma"),
						def("Delta", "delta_id", "delta")),
				List.of(
						edge("Alpha", "Beta", "alpha_id", "alpha_ref"),
						edge("Alpha", "Gamma", "alpha_id", "alpha_ref"),
						edge("Beta", "Delta", "beta_id", "beta_ref"),
						edge("Gamma", "Delta", "gamma_id", "gamma_ref")));
		List<EntityRecord> records = List.of(
				record("Alpha", "A1", 1, Map.of("alpha_id", "A1", "label", "alpha")),
				record("Beta", "B1", 2, Map.of("beta_id", "B1", "alpha_ref", "A1", "label", "beta")),
				record("Gamma", "C1", 3, Map.of("gamma_id", "C1", "alpha_ref", "A1", "label", "gamma")),
				record("Delta", "D1", 4, Map.of("delta_id", "D1", "beta_ref", "B1", "gamma_ref", "C1", "label", "delta")));
		MapLookup lookup = lookup(Map.of(
				"Alpha", Map.of("A1", "TA1"),
				"Beta", Map.of("B1", "TB1"),
				"Gamma", Map.of("C1", "TC1"),
				"Delta", Map.of("D1", "TD1")));

		runRecords(mapping, nameRules("Alpha", "Beta", "Gamma", "Delta"), records, lookup, 10);

		assertThat(loadedIdentities().stream().filter(label -> label.equals("Delta:D1"))).hasSize(1);
		assertThat(parentTargets(payload("Delta", "D1"))).containsExactly("TB1", "TC1");
	}

	@Test
	void usesTheEntitySourceKeyInsteadOfTheRelationshipKey() {
		EntityRelationshipMapping mapping = mapping(
				"Customer",
				List.of(def("Customer", "customer_id", "party"), def("Account", "account_id", "account")),
				List.of(edge("Customer", "Account", "customer_id", "customer_id")));
		List<EntityRecord> records = List.of(
				record("Customer", "C001", 1, Map.of("customer_id", "C001", "label", "Ada")),
				record("Account", "A001", 2, Map.of("account_id", "A001", "customer_id", "C001", "label", "Gold")));
		MapLookup lookup = lookup(Map.of(
				"Customer", Map.of("C001", "TC001"),
				"Account", Map.of("A001", "TA001")));

		RunResult result = runRecords(mapping, nameRules("Customer", "Account"), records, lookup, 10);
		ExecutionWorkItem account = result.plans().stream()
				.flatMap(plan -> plan.workItems().stream())
				.filter(item -> item.entityName().equals("Account"))
				.findFirst()
				.orElseThrow();

		assertThat(account.sourceIdentity()).isEqualTo("A001");
		assertThat(account.parents()).extracting(WorkItemParent::sourceIdentity).containsExactly("C001");
		Map<String, Object> payload = payload("Account", "A001");
		assertThat(payload.get("sourceIdentity")).isEqualTo("A001");
		assertThat(payload.get("targetIdentity")).isEqualTo("TA001");
		assertThat(payload.get("sourceKey")).isEqualTo("account_id");
		assertThat(payload.get("targetKey")).isEqualTo("id");
		assertThat(parentTargets(payload)).containsExactly("TC001");
	}

	@Test
	void runsAProductModelAndAnArbitraryModel() {
		runChain("Product", "Variant", "Price");
		assertThat(loadedIdentities()).containsExactlyInAnyOrder("Product:A1", "Variant:B1", "Price:G1");
		assertThat(parentTargets(payload("Price", "G1"))).containsExactly("TB1");
		target = new RecordingTarget();
		runChain("Alpha", "Beta", "Gamma");

		assertThat(loadedIdentities()).containsExactlyInAnyOrder("Alpha:A1", "Beta:B1", "Gamma:G1");
		assertThat(parentTargets(payload("Gamma", "G1"))).containsExactly("TB1");
	}

	@Test
	void reportsAnExtractionFailureWithTheEntity() throws Exception {
		write("broken.csv", """
				location_id,location_name,address_id,address_name,address_location_id
				L001,Main,,,
				,,," Home ",
				""");
		List<EntityItemFailure> failures = new ArrayList<>();
		List<ExecutionBatchPlan> plans = new ArrayList<>();
		planSource.consume(singleConfig("broken.csv"), placeModel(), placeRules(), 10, plans::add, failures::add);

		assertThat(failures).anySatisfy(failure -> {
			assertThat(failure.entityName()).isEqualTo("Address");
			assertThat(failure.stage()).isEqualTo(MigrationStage.EXTRACT);
			assertThat(failure.sourceRecordNumber()).isEqualTo(2L);
		});
		assertThat(plans.stream().flatMap(plan -> plan.workItems().stream()).map(ExecutionWorkItem::entityName))
				.contains("Location");
	}

	@Test
	void resolvesLocationGeometryToGeographicLocationFromTheSameCsvRow() throws Exception {
		write("geometry.csv", """
				street_name,city,longitude,latitude
				Sample,Melbourne,144.91,-37.79
				""");
		EntityRelationshipMapping mapping = geometryModel(null);
		List<MigrationMappingRule> rules = geometryRules();
		List<ExecutionBatchPlan> plans = new ArrayList<>();
		List<EntityItemFailure> failures = new ArrayList<>();
		planSource.consume(singleConfig("geometry.csv"), mapping, rules, 10, plans::add, failures::add);

		assertThat(failures).isEmpty();
		ExecutionWorkItem geometry = plans.stream()
				.flatMap(plan -> plan.workItems().stream())
				.filter(item -> item.entityName().equals("LocationGeometry"))
				.findFirst()
				.orElseThrow();
		assertThat(geometry.sourceIdentity()).isEqualTo("1");
		assertThat(geometry.sourceRecordNumber()).isEqualTo(1L);
		assertThat(geometry.parents()).anySatisfy(parent -> {
			assertThat(parent.entityName()).isEqualTo("GeographicLocation");
			assertThat(parent.sourceIdentity()).isEqualTo("1");
		});

		MapLookup lookup = lookup(Map.of(
				"AddressLocation", Map.of("1", "LOC-000000001"),
				"GeographicAddress", Map.of("1", "ADDR-0000000001"),
				"GeographicLocation", Map.of("1", "GEO-000000001"),
				"LocationGeometry", Map.of("1", "GEOM-000000001")));
		EntityCsvAccess access = planSourceCsv();
		var records = access.open(singleConfig("geometry.csv"), mapping, rules);
		var payloads = records.matching(wanted(plans.get(0)));
		try {
			execute(mapping, rules, plans.get(0), payloads, lookup, 10);
		} finally {
			payloads.clear();
		}

		Map<String, Object> payload = payload("LocationGeometry", "1");
		assertThat(payload.get("sourceKey")).isNull();
		assertThat(parentTargets(payload)).containsExactly("GEO-000000001");
		assertThat(foreignKey(payload, "GeographicLocation"))
				.containsEntry("column", "location_id")
				.containsEntry("referencesColumn", "id");
	}

	@Test
	void reportsAMissingGeographicLocationWhenTheParentRowWasNotExtracted() throws Exception {
		write("geometry-missing.csv", """
				street_name,city,longitude,latitude
				Sample,Melbourne,144.91,-37.79
				""");
		EntityRelationshipMapping mapping = geometryModel("geo_id");
		List<ExecutionBatchPlan> plans = new ArrayList<>();
		List<EntityItemFailure> failures = new ArrayList<>();
		planSource.consume(singleConfig("geometry-missing.csv"), mapping, geometryRules(), 10, plans::add, failures::add);

		assertThat(failures).anySatisfy(failure -> {
			assertThat(failure.entityName()).isEqualTo("LocationGeometry");
			assertThat(failure.sourceIdentity()).isEqualTo("1");
			assertThat(failure.sourceRecordNumber()).isEqualTo(1L);
			assertThat(failure.errorCode()).isEqualTo(MigrationErrorCodes.RELATIONSHIP_UNRESOLVED);
			assertThat(failure.message()).contains("relationship key source record '1' does not match a parent.");
		});
		assertThat(plans.stream().flatMap(plan -> plan.workItems().stream()).map(ExecutionWorkItem::entityName))
				.doesNotContain("LocationGeometry")
				.contains("AddressLocation", "GeographicAddress");
	}

	@Test
	void reportsAMissingRelationshipParent() {
		List<EntityRecord> records = List.of(
				record("Location", "L001", 1, Map.of("location_id", "L001", "location_name", "Main")),
				record("Address", "A001", 2, Map.of("address_id", "A001", "address_name", "One", "address_location_id", "L999")));
		List<EntityItemFailure> failures = new ArrayList<>();
		List<ExecutionBatchPlan> plans = new ArrayList<>();
		planSource.consume(placeModel(), records, 10, plans::add, failures::add);

		assertThat(failures).anySatisfy(failure -> {
			assertThat(failure.entityName()).isEqualTo("Address");
			assertThat(failure.sourceIdentity()).isEqualTo("A001");
			assertThat(failure.errorCode()).isEqualTo(MigrationErrorCodes.RELATIONSHIP_UNRESOLVED);
		});
		assertThat(plans.stream().flatMap(plan -> plan.workItems().stream()).map(ExecutionWorkItem::sourceIdentity))
				.doesNotContain("A001");
	}

	@Test
	void stagesAnUnresolvedTargetIdentity() {
		List<EntityRecord> records = List.of(
				record("Address", "A999", 4, Map.of("address_id", "A999", "address_name", "Missing")));
		EntityRelationshipMapping mapping = mapping(
				"Address",
				List.of(def("Address", "address_id", "geographic_address")),
				List.of());
		ExecutionBatchPlan plan = new ExecutionBatchPlan(1, List.of(item("Address", "A999", 4, "A999")));
		EntityBatchOutcome outcome = execute(
				mapping,
				List.of(rule("Address", 1, "address_name", "name", TransformationType.DIRECT, false)),
				plan,
				records(records, plan),
				lookup(Map.of()),
				10);

		assertThat(outcome.failures()).isEmpty();
		assertThat(outcome.counters().successCount()).isEqualTo(1);
		assertThat(loadedIdentities()).containsExactly("Address:A999");
		Map<String, Object> payload = payload("Address", "A999");
		assertThat(payload.get("targetIdentity")).isNull();
		assertThat(payload.get("targetEntity")).isEqualTo("geographic_address");
		assertThat(payload.get("sourceIdentity")).isEqualTo("A999");
		assertThat(payload.get("sourceKey")).isEqualTo("address_id");
		assertThat(attributes(payload).get("name")).isEqualTo("Missing");
	}

	@Test
	void isolatesAValidationFailure() {
		List<EntityRecord> records = List.of(
				record("Address", "A001", 1, Map.of("address_id", "A001", "address_name", "One")),
				record("Address", "A002", 2, Map.of("address_id", "A002", "address_name", " ")));
		EntityRelationshipMapping mapping = mapping(
				"Address",
				List.of(def("Address", "address_id", "geographic_address")),
				List.of());
		List<MigrationMappingRule> rules = List.of(mandatoryName("Address", 1));
		ExecutionBatchPlan plan = new ExecutionBatchPlan(1, List.of(
				item("Address", "A001", 1, "A001"),
				item("Address", "A002", 2, "A002")));

		EntityBatchOutcome outcome = execute(
				mapping,
				rules,
				plan,
				records(records, plan),
				lookup(Map.of("Address", Map.of("A001", "TA001", "A002", "TA002"))),
				10);

		assertThat(loadedIdentities()).containsExactly("Address:A001");
		assertThat(outcome.failures()).anySatisfy(failure -> {
			assertThat(failure.entityName()).isEqualTo("Address");
			assertThat(failure.sourceIdentity()).isEqualTo("A002");
			assertThat(failure.stage()).isEqualTo(MigrationStage.VALIDATE);
		});
		assertThat(outcome.counters().successCount()).isEqualTo(1);
		assertThat(outcome.counters().validationFailureCount()).isEqualTo(1);
	}

	@Test
	void isolatesATransformationFailure() {
		EntityRelationshipMapping mapping = mapping(
				"Location",
				List.of(def("Location", "location_id", "geographic_location"), def("Address", "address_id", "geographic_address")),
				List.of(edge("Location", "Address", "location_id", "address_location_id")));
		List<MigrationMappingRule> rules = List.of(
				rule("Location", 1, "location_name", "name", TransformationType.DIRECT, false),
				rule("Address", 2, "address_name", "name", TransformationType.LOOKUP, false));
		List<EntityRecord> records = List.of(
				record("Location", "L001", 1, Map.of("location_id", "L001", "location_name", "Main")),
				record("Address", "A001", 2, Map.of("address_id", "A001", "address_name", "One", "address_location_id", "L001")));
		ExecutionBatchPlan plan = plan(mapping, records, 10).plans().get(0);

		EntityBatchOutcome outcome = execute(mapping, rules, plan, records(records, plan), placeLookup(), 10);

		assertThat(loadedIdentities()).containsExactly("Location:L001");
		assertThat(outcome.failures()).anySatisfy(failure -> {
			assertThat(failure.entityName()).isEqualTo("Address");
			assertThat(failure.sourceIdentity()).isEqualTo("A001");
			assertThat(failure.stage()).isEqualTo(MigrationStage.TRANSFORM);
		});
	}

	@Test
	void reportsATargetLoadFailureForThatEntity() {
		target.failRecord = 2L;
		List<EntityRecord> records = List.of(
				record("Location", "L001", 1, Map.of("location_id", "L001", "location_name", "Main")),
				record("Address", "A001", 2, Map.of("address_id", "A001", "address_name", "One", "address_location_id", "L001")));
		EntityRelationshipMapping mapping = placeModel();
		ExecutionBatchPlan plan = plan(mapping, records, 10).plans().get(0);

		EntityBatchOutcome outcome = execute(mapping, placeRules(), plan, records(records, plan), placeLookup(), 10);

		assertThat(outcome.counters().successCount()).isEqualTo(1);
		assertThat(outcome.failures()).anySatisfy(failure -> {
			assertThat(failure.entityName()).isEqualTo("Address");
			assertThat(failure.sourceIdentity()).isEqualTo("A001");
			assertThat(failure.stage()).isEqualTo(MigrationStage.LOAD);
		});
	}

	@Test
	void stagesAChildWhenTheParentTargetIdentityIsUnavailable() {
		ExecutionBatchPlan batch = new ExecutionBatchPlan(2, List.of(
				item("Site", "S001", 3, "L001", parent("Address", "A001"))));
		EntityBatchOutcome outcome = execute(
				placeModel(),
				placeRules(),
				batch,
				records(placeRecords(), batch),
				lookup(Map.of("Site", Map.of("S001", "TS001"))),
				10);

		assertThat(outcome.failures()).isEmpty();
		assertThat(outcome.counters().successCount()).isEqualTo(1);
		Map<String, Object> site = payload("Site", "S001");
		assertThat(site.get("targetIdentity")).isEqualTo("TS001");
		assertThat(relationships(site)).singleElement().satisfies(link -> {
			assertThat(link.get("parentEntity")).isEqualTo("Address");
			assertThat(link.get("parentSourceIdentity")).isEqualTo("A001");
			assertThat(link.get("parentTargetIdentity")).isNull();
		});
	}

	@Test
	void stagesEveryEntityWhenNoTargetIdentityIsAllocated() {
		EntityRelationshipMapping mapping = placeModel();
		List<EntityRecord> records = List.of(
				record("Location", "L001", 1, Map.of("location_id", "L001", "location_name", "Main")),
				record("Address", "A001", 2, Map.of(
						"address_id", "A001", "address_name", "One", "address_location_id", "L001")));
		ExecutionBatchPlan plan = new ExecutionBatchPlan(1, List.of(
				item("Location", "L001", 1, "L001"),
				item("Address", "A001", 2, "L001", parent("Location", "L001"))));
		EntityBatchOutcome outcome = execute(mapping, placeRules(), plan, records(records, plan), lookup(Map.of()), 10);

		assertThat(outcome.failures()).isEmpty();
		assertThat(outcome.counters().successCount()).isEqualTo(2);
		assertThat(loadedIdentities()).containsExactlyInAnyOrder("Location:L001", "Address:A001");
		assertThat(payload("Location", "L001").get("targetIdentity")).isNull();
		Map<String, Object> address = payload("Address", "A001");
		assertThat(address.get("targetIdentity")).isNull();
		assertThat(address.get("entity")).isEqualTo("Address");
		assertThat(address.get("targetEntity")).isEqualTo("geographic_address");
		assertThat(address.get("sourceKey")).isEqualTo("address_id");
		assertThat(relationships(address)).singleElement().satisfies(link -> {
			assertThat(link.get("parentEntity")).isEqualTo("Location");
			assertThat(link.get("parentSourceIdentity")).isEqualTo("L001");
			assertThat(link.get("parentTargetIdentity")).isNull();
		});
	}

	@Test
	void chunksIdentityLookupAndReleasesEachBatch() {
		List<EntityRecord> records = new ArrayList<>();
		Map<String, String> ids = new LinkedHashMap<>();
		for (int i = 1; i <= 30; i++) {
			String source = "N" + i;
			records.add(record("Node", source, i, Map.of("node_id", source, "label", "n" + i)));
			ids.put(source, "T" + i);
		}
		EntityRelationshipMapping mapping = mapping(
				"Node",
				List.of(def("Node", "node_id", "node")),
				List.of());
		MapLookup lookup = lookup(Map.of("Node", ids));
		int[] maxHeld = {0};
		List<String> firstLoad = new ArrayList<>();
		planSource.consume(mapping, records, 10, plan -> {
			Map<EntityIdentity, EntityRecord> selected = records(records, plan);
			maxHeld[0] = Math.max(maxHeld[0], selected.size());
			assertThat(selected).hasSizeLessThanOrEqualTo(10);
			execute(mapping, nameRules("Node"), plan, selected, lookup, 10);
			if (firstLoad.isEmpty()) {
				firstLoad.addAll(target.loads.get(0).stream().map(EntityRelationshipExecutionTest::label).toList());
			}
			selected.clear();
			assertThat(selected).isEmpty();
		}, failure -> {
		});

		assertThat(maxHeld[0]).isLessThanOrEqualTo(10);
		assertThat(firstLoad).hasSize(10).doesNotContain("Node:N11");
		assertThat(target.loads).hasSize(3);
		assertThat(lookup.calls).allSatisfy(call -> assertThat(call.sourceIdentities()).hasSizeLessThanOrEqualTo(10));
		assertThat(lookup.calls.stream().mapToInt(call -> call.sourceIdentities().size()).sum()).isEqualTo(30);
	}

	@Test
	void countsLogicalWorkItemsSeparatelyFromPhysicalRows() throws Exception {
		write("addresses.csv", """
				address_id,unit
				A1,
				A2,
				A3,U1
				A4,U2
				""");
		EntityRelationshipMapping mapping = mapping(
				"House",
				List.of(def("House", "address_id", "house"), def("Unit", null, "unit")),
				List.of(new RelationshipDefinition(
						"House",
						"Unit",
						RelationshipCardinality.ONE_TO_MANY,
						List.of())));
		List<MigrationMappingRule> rules = List.of(
				rule("Unit", 1, "unit", "name", TransformationType.DIRECT, false));
		Map<String, Object> source = singleConfig("addresses.csv");
		assertThat(planSource.physicalRowCount(source, mapping, rules)).hasValue(4L);

		List<ExecutionBatchPlan> plans = new ArrayList<>();
		planSource.consume(source, mapping, rules, 2, plans::add, failure -> {
			throw new AssertionError(failure.message());
		});

		long workItems = plans.stream().mapToLong(plan -> plan.workItems().size()).sum();
		assertThat(workItems).isEqualTo(6L);
		assertThat(plans).hasSize(3);
		assertThat(plans).allSatisfy(plan -> assertThat(plan.workItems()).hasSizeLessThanOrEqualTo(2));
		assertThat(planSource.physicalRowCount(source, mapping, rules)).hasValue(4L);
	}

	@Test
	void leavesFlatMappingsOnTheRecordPipeline() {
		assertThat(ExecutionMode.entityRelationship(Map.of())).isFalse();
		assertThat(ExecutionMode.entityRelationship(Map.of(
				"modelType", "ENTITY_RELATIONSHIP",
				"rootEntity", "Node",
				"entities", List.of(Map.of(
						"name", "Node",
						"targetEntity", "node",
						"sourceKey", "node_id",
						"targetKey", "id")),
				"relationships", List.of()))).isTrue();
	}

	private RunResult runRecords(
			EntityRelationshipMapping mapping,
			List<MigrationMappingRule> rules,
			List<EntityRecord> records,
			MapLookup lookup,
			int batchSize) {
		RunResult planned = plan(mapping, records, batchSize);
		for (ExecutionBatchPlan batch : planned.plans()) {
			execute(mapping, rules, batch, records(records, batch), lookup, 100);
		}
		return planned;
	}

	private void runCsv(
			Map<String, Object> source,
			EntityRelationshipMapping mapping,
			List<MigrationMappingRule> rules,
			MapLookup lookup,
			int batchSize) {
		List<ExecutionBatchPlan> plans = new ArrayList<>();
		planSource.consume(source, mapping, rules, batchSize, plans::add, failure -> {
		});
		EntityCsvAccess access = planSourceCsv();
		for (ExecutionBatchPlan plan : plans) {
			var records = access.open(source, mapping, rules);
			var payloads = records.matching(wanted(plan));
			try {
				execute(mapping, rules, plan, payloads, lookup, 100);
			} finally {
				payloads.clear();
			}
		}
	}

	private EntityCsvAccess planSourceCsv() {
		FileSystemArtifactStorage files = new FileSystemArtifactStorage(tempDir);
		files.initializeRoot();
		CsvSourceConnector csv = new CsvSourceConnector(new DefaultArtifactStorage(files));
		MappingEngine mappingEngine = new MappingEngine(new TransformationEngine());
		EntityExtractor extractor = new EntityExtractor(mappingEngine);
		return new EntityCsvAccess(csv, extractor, new MultiFileEntitySource(csv, extractor));
	}

	private RunResult plan(EntityRelationshipMapping mapping, List<EntityRecord> records, int batchSize) {
		List<ExecutionBatchPlan> plans = new ArrayList<>();
		List<EntityItemFailure> failures = new ArrayList<>();
		planSource.consume(mapping, records, batchSize, plans::add, failures::add);
		return new RunResult(plans, failures);
	}

	private void runChain(String first, String second, String third) {
		EntityRelationshipMapping mapping = mapping(
				first,
				List.of(def(first, "a_id", "a"), def(second, "b_id", "b"), def(third, "c_id", "c")),
				List.of(edge(first, second, "a_id", "a_ref"), edge(second, third, "b_id", "b_ref")));
		List<EntityRecord> records = List.of(
				record(first, "A1", 1, Map.of("a_id", "A1", "label", first)),
				record(second, "B1", 2, Map.of("b_id", "B1", "a_ref", "A1", "label", second)),
				record(third, "G1", 3, Map.of("c_id", "G1", "b_ref", "B1", "label", third)));
		MapLookup lookup = lookup(Map.of(
				first, Map.of("A1", "TA1"),
				second, Map.of("B1", "TB1"),
				third, Map.of("G1", "TG1")));
		runRecords(mapping, nameRules(first, second, third), records, lookup, 10);
	}

	private EntityBatchOutcome execute(
			EntityRelationshipMapping mapping,
			List<MigrationMappingRule> rules,
			ExecutionBatchPlan plan,
			Map<com.etp.migration.identity.EntityIdentity, EntityRecord> payloads,
			TargetIdentityLookup lookup,
			int chunkSize) {
		return executor.execute(
				mapping,
				rules,
				plan,
				payloads,
				lookup,
				chunkSize,
				target,
				new MigrationTargetContext(UUID.randomUUID(), 9L, plan.batchNumber(), "TEST", "staged", LoadMode.BULK, Map.of()));
	}

	private static Map<EntityIdentity, EntityRecord> records(List<EntityRecord> records, ExecutionBatchPlan plan) {
		Map<EntityIdentity, EntityRecord> available = new LinkedHashMap<>();
		for (EntityRecord record : records) {
			available.putIfAbsent(new EntityIdentity(record.entityName(), record.sourceIdentity()), record);
		}
		Map<EntityIdentity, EntityRecord> selected = new LinkedHashMap<>();
		for (EntityIdentity identity : wanted(plan)) {
			if (available.containsKey(identity)) {
				selected.put(identity, available.get(identity));
			}
		}
		return selected;
	}

	private static java.util.Set<EntityIdentity> wanted(ExecutionBatchPlan plan) {
		java.util.Set<EntityIdentity> wanted = new java.util.LinkedHashSet<>();
		for (ExecutionWorkItem item : plan.workItems()) {
			wanted.add(new EntityIdentity(item.entityName(), item.sourceIdentity()));
		}
		return wanted;
	}

	private List<String> loadedIdentities() {
		return target.loads.stream().flatMap(List::stream).map(EntityRelationshipExecutionTest::label).toList();
	}

	private List<String> signatures() {
		return target.loads.stream().flatMap(List::stream).map(payload -> label(payload) + "->" + payload.get("targetIdentity")
				+ parentsSignature(payload)).sorted().toList();
	}

	private Map<String, Object> payload(String entity, String source) {
		return target.loads.stream()
				.flatMap(List::stream)
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

	@SuppressWarnings("unchecked")
	private static List<String> parentTargets(Map<String, Object> payload) {
		List<Map<String, Object>> relationships = (List<Map<String, Object>>) payload.get("relationships");
		return relationships.stream().map(link -> String.valueOf(link.get("parentTargetIdentity"))).toList();
	}

	@SuppressWarnings("unchecked")
	private static String parentsSignature(Map<String, Object> payload) {
		List<Map<String, Object>> relationships = (List<Map<String, Object>>) payload.get("relationships");
		return relationships.stream()
				.map(link -> link.get("parentEntity") + ":" + link.get("parentTargetIdentity"))
				.toList()
				.toString();
	}

	private static String label(Map<String, Object> payload) {
		return payload.get("entity") + ":" + payload.get("sourceIdentity");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> foreignKey(Map<String, Object> payload, String parentEntity) {
		List<Map<String, Object>> relationships = (List<Map<String, Object>>) payload.get("relationships");
		return relationships.stream()
				.filter(link -> parentEntity.equals(link.get("parentEntity")))
				.map(link -> (Map<String, Object>) link.get("targetForeignKey"))
				.findFirst()
				.orElseThrow();
	}

	private static EntityRelationshipMapping geometryModel(String geographicLocationSourceKey) {
		return mapping(
				"AddressLocation",
				List.of(
						new EntityDefinition("AddressLocation", "location", null, "id"),
						new EntityDefinition("GeographicAddress", "geographic_address", null, "id"),
						new EntityDefinition("GeographicLocation", "geographic_location", geographicLocationSourceKey, "id"),
						new EntityDefinition("LocationGeometry", "location_geometry", null, "id")),
				List.of(
						foreignKey("AddressLocation", "GeographicAddress", "location_id", "id"),
						foreignKey("GeographicLocation", "GeographicAddress", "geographic_location_id", "id"),
						foreignKey("GeographicLocation", "LocationGeometry", "location_id", "id")));
	}

	private static RelationshipDefinition foreignKey(
			String parent,
			String child,
			String column,
			String referencesColumn) {
		return new RelationshipDefinition(
				parent,
				child,
				RelationshipCardinality.ONE_TO_ONE,
				List.of(),
				new TargetForeignKey(column, referencesColumn));
	}

	private static List<MigrationMappingRule> geometryRules() {
		MigrationMappingRule street = rule("AddressLocation", 1, "street_name", "name", TransformationType.DIRECT, false);
		MigrationMappingRule city = rule("GeographicAddress", 2, "city", "city", TransformationType.DIRECT, false);
		MigrationMappingRule geoType = new MigrationMappingRule();
		geoType.setEntityName("GeographicLocation");
		geoType.setSequenceNo(3);
		geoType.setTargetField("geo_json_type");
		geoType.setTransformationType(TransformationType.DEFAULT);
		geoType.setDefaultValue("geoJsonPoint");
		geoType.setEnabled(true);
		geoType.setConfiguration(Map.of());
		MigrationMappingRule longitude = rule("LocationGeometry", 4, "longitude", "x", TransformationType.DIRECT, false);
		return List.of(street, city, geoType, longitude);
	}

	private static EntityRelationshipMapping placeModel() {
		return mapping(
				"Location",
				List.of(
						def("Location", "location_id", "geographic_location"),
						def("Address", "address_id", "geographic_address"),
						def("Site", "site_id", "geographic_site")),
				List.of(
						edge("Location", "Address", "location_id", "address_location_id"),
						edge("Address", "Site", "address_id", "site_address_id")));
	}

	private static List<MigrationMappingRule> placeRules() {
		return List.of(
				rule("Location", 1, "location_name", "name", TransformationType.TRIM, false),
				rule("Address", 2, "address_name", "name", TransformationType.TRIM, true),
				rule("Site", 3, "site_name", "name", TransformationType.TRIM, false));
	}

	private static List<EntityRecord> placeRecords() {
		return List.of(
				record("Location", "L001", 1, Map.of("location_id", "L001", "location_name", " Main ")),
				record("Address", "A001", 2, Map.of("address_id", "A001", "address_name", " One ", "address_location_id", "L001")),
				record("Address", "A002", 3, Map.of("address_id", "A002", "address_name", " Two ", "address_location_id", "L001")),
				record("Site", "S001", 4, Map.of("site_id", "S001", "site_name", "S1", "site_address_id", "A001")),
				record("Site", "S002", 5, Map.of("site_id", "S002", "site_name", "S2", "site_address_id", "A001")),
				record("Site", "S003", 6, Map.of("site_id", "S003", "site_name", "S3", "site_address_id", "A002")));
	}

	private static MapLookup placeLookup() {
		return lookup(Map.of(
				"Location", Map.of("L001", "TL001"),
				"Address", Map.of("A001", "TA001", "A002", "TA002"),
				"Site", Map.of("S001", "TS001", "S002", "TS002", "S003", "TS003")));
	}

	private static List<MigrationMappingRule> nameRules(String... entities) {
		List<MigrationMappingRule> rules = new ArrayList<>();
		int sequence = 1;
		for (String entity : entities) {
			rules.add(rule(entity, sequence++, "label", "name", TransformationType.DIRECT, false));
		}
		return rules;
	}

	private static MigrationMappingRule mandatoryName(String entity, int sequence) {
		return rule(entity, sequence, "address_name", "name", TransformationType.TRIM, true);
	}

	private static MigrationMappingRule rule(
			String entity,
			int sequence,
			String source,
			String targetField,
			TransformationType type,
			boolean mandatory) {
		MigrationMappingRule rule = new MigrationMappingRule();
		rule.setEntityName(entity);
		rule.setSequenceNo(sequence);
		rule.setSourceField(source);
		rule.setTargetField(targetField);
		rule.setTransformationType(type);
		rule.setMandatory(mandatory);
		rule.setEnabled(true);
		rule.setConfiguration(Map.of());
		return rule;
	}

	private static EntityRecord record(String entity, String source, long number, Map<String, Object> fields) {
		return new EntityRecord(entity, number, source, fields);
	}

	private static ExecutionWorkItem item(String entity, String source, long number, String root, WorkItemParent... parents) {
		return new ExecutionWorkItem(entity, source, number, rootEntity(entity), root, List.of(parents));
	}

	private static String rootEntity(String entity) {
		return "Site".equals(entity) || "Address".equals(entity) ? "Location" : entity;
	}

	private static WorkItemParent parent(String entity, String source) {
		return new WorkItemParent(entity, source);
	}

	private static EntityRelationshipMapping mapping(
			String root,
			List<EntityDefinition> entities,
			List<RelationshipDefinition> relationships) {
		return new EntityRelationshipMapping(root, entities, relationships);
	}

	private static EntityDefinition def(String name, String sourceKey, String targetEntity) {
		return new EntityDefinition(name, targetEntity, sourceKey, "id");
	}

	private static RelationshipDefinition edge(String parent, String child, String parentField, String childField) {
		return new RelationshipDefinition(
				parent,
				child,
				RelationshipCardinality.ONE_TO_MANY,
				List.of(new RelationshipKey(parentField, childField)));
	}

	private static MapLookup lookup(Map<String, Map<String, String>> data) {
		return new MapLookup(data);
	}

	private Map<String, Object> singleConfig(String objectKey) {
		Map<String, Object> config = new LinkedHashMap<>();
		config.put("provider", "FILE_SYSTEM");
		config.put("objectKey", objectKey);
		config.put("fileFormat", "CSV");
		config.put("hasHeader", true);
		return config;
	}

	private Map<String, Object> multiConfig() {
		Map<String, Object> files = new LinkedHashMap<>();
		files.put("Location", Map.of("file", "location.csv"));
		files.put("Address", Map.of("file", "address.csv"));
		files.put("Site", Map.of("file", "site.csv"));
		Map<String, Object> config = new LinkedHashMap<>();
		config.put("layoutType", "MULTI_FILE");
		config.put("provider", "FILE_SYSTEM");
		config.put("fileFormat", "CSV");
		config.put("hasHeader", true);
		config.put("files", files);
		return config;
	}

	private void write(String name, String content) throws Exception {
		Files.writeString(tempDir.resolve(name), content);
	}

	private record RunResult(List<ExecutionBatchPlan> plans, List<EntityItemFailure> failures) {
	}

	private static final class MapLookup implements TargetIdentityLookup {
		private final Map<String, Map<String, String>> data;
		private final List<Call> calls = new ArrayList<>();

		private MapLookup(Map<String, Map<String, String>> data) {
			this.data = data;
		}

		@Override
		public Map<String, String> lookup(String entityName, List<String> sourceIdentities) {
			calls.add(new Call(entityName, List.copyOf(sourceIdentities)));
			Map<String, String> known = data.getOrDefault(entityName, Map.of());
			Map<String, String> resolved = new LinkedHashMap<>();
			for (String sourceIdentity : sourceIdentities) {
				if (known.containsKey(sourceIdentity)) {
					resolved.put(sourceIdentity, known.get(sourceIdentity));
				}
			}
			return resolved;
		}
	}

	private record Call(String entityName, List<String> sourceIdentities) {
	}

	private static final class RecordingTarget implements MigrationTargetConnector {
		private final List<List<Map<String, Object>>> loads = new ArrayList<>();
		private Long failRecord;

		@Override
		public String targetType() {
			return "TEST";
		}

		@Override
		public boolean supportsLoadMode(LoadMode loadMode) {
			return true;
		}

		@Override
		public TargetLoadResult load(MigrationTargetContext context, List<LoadableTargetRecord> records) {
			List<Map<String, Object>> payloads = new ArrayList<>();
			List<TargetRecordError> errors = new ArrayList<>();
			int success = 0;
			int failed = 0;
			for (LoadableTargetRecord record : records) {
				payloads.add(record.targetRecord().fields());
				if (failRecord != null && failRecord == record.sourceRecordNumber()) {
					failed++;
					errors.add(new TargetRecordError(
							record.sourceRecordNumber(),
							"MIGRATION_TARGET_COPY_FAILED",
							"LOAD_FAILED",
							"load failed",
							false,
							null));
				} else {
					success++;
				}
			}
			loads.add(payloads);
			return new TargetLoadResult(records.size(), success, failed, List.of(), errors);
		}
	}
}
