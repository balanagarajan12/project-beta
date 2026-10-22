package com.etp.migration.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.etp.migration.connector.source.csv.CsvSourceConnector;
import com.etp.migration.connector.storage.DefaultArtifactStorage;
import com.etp.migration.connector.storage.FileSystemArtifactStorage;
import com.etp.migration.entity.MigrationBatch;
import com.etp.migration.entity.MigrationMappingRule;
import com.etp.migration.entity.MigrationSource;
import com.etp.migration.entity.TransformationType;
import com.etp.migration.mapping.EntityDefinition;
import com.etp.migration.mapping.EntityRelationshipMapping;
import com.etp.migration.processing.SourceRecord;
import com.etp.migration.transformation.MappingEngine;
import com.etp.migration.transformation.TransformationEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EntityExtractorTest {

	private final MappingEngine mappingEngine = new MappingEngine(new TransformationEngine());
	private final EntityExtractor extractor = new EntityExtractor(mappingEngine);

	@TempDir
	Path tempDir;

	private CsvSourceConnector csv;

	@BeforeEach
	void setUp() {
		FileSystemArtifactStorage fileSystem = new FileSystemArtifactStorage(tempDir);
		fileSystem.initializeRoot();
		csv = new CsvSourceConnector(new DefaultArtifactStorage(fileSystem));
	}

	@Test
	void emitsAConstantEntityForTheSourceRecordWithoutASourceKey() {
		EntityDefinition root = new EntityDefinition("AddressLocation", "location", null, "id");
		EntityDefinition location = new EntityDefinition("GeographicLocation", "geographic_location", null, "id");
		EntityDefinition geometry = new EntityDefinition("LocationGeometry", "location_geometry", null, "id");
		MigrationMappingRule street = rule(1, "street_name", "name");
		street.setEntityName("AddressLocation");
		MigrationMappingRule geoType = constant(2, "geo_json_type", "geoJsonPoint");
		geoType.setEntityName("GeographicLocation");
		MigrationMappingRule longitude = rule(3, "longitude", "x");
		longitude.setEntityName("LocationGeometry");
		EntityRelationshipMapping mapping = new EntityRelationshipMapping(
				"AddressLocation",
				List.of(root, location, geometry),
				List.of());

		EntityExtraction extraction = extractor.extract(
				row(1, Map.of("street_name", "Sample", "longitude", "144.91")),
				mapping,
				List.of(street, geoType, longitude));

		assertThat(extraction.failures()).isEmpty();
		assertThat(identity(extraction, "AddressLocation")).isEqualTo("1");
		assertThat(identity(extraction, "GeographicLocation")).isEqualTo("1");
		assertThat(identity(extraction, "LocationGeometry")).isEqualTo("1");
		assertThat(record(extraction, "GeographicLocation").fields()).isEmpty();
		assertThat(record(extraction, "LocationGeometry").fields()).containsEntry("longitude", "144.91");
	}

	@Test
	void omitsASourceRecordEntityWhenItsSourceColumnsAreBlank() {
		EntityDefinition location = new EntityDefinition("GeographicLocation", "geographic_location", null, "id");
		EntityDefinition subAddress = new EntityDefinition("GeographicSubAddress", "geographic_sub_address", null, "id");
		MigrationMappingRule geoType = constant(1, "geo_json_type", "geoJsonPoint");
		geoType.setEntityName("GeographicLocation");
		MigrationMappingRule unit = rule(2, "unit_number", "sub_unit_number");
		unit.setEntityName("GeographicSubAddress");
		EntityRelationshipMapping mapping = new EntityRelationshipMapping(
				"GeographicLocation",
				List.of(location, subAddress),
				List.of());

		EntityExtraction extraction = extractor.extract(
				row(1, Map.of("unit_number", "  ")),
				mapping,
				List.of(geoType, unit));

		assertThat(extraction.failures()).isEmpty();
		assertThat(extraction.records()).extracting(EntityRecord::entityName).containsExactly("GeographicLocation");
	}

	@Test
	void nullSourceKeyUsesTheSourceRecordAndDoesNotReadANullField() {
		EntityDefinition parent = new EntityDefinition("GeographicAddress", "geographic_address", "address_id", "location_id");
		EntityDefinition child = new EntityDefinition("GeographicSubAddress", "geographic_sub_address", null, "id");
		MigrationMappingRule childRule = rule(1, "sub_unit_number", "sub_unit_number");
		childRule.setEntityName("GeographicSubAddress");
		MigrationMappingRule parentRule = rule(2, "address_id", "location_id");
		parentRule.setEntityName("GeographicAddress");
		EntityRelationshipMapping mapping = new EntityRelationshipMapping(
				"GeographicAddress",
				List.of(parent, child),
				List.of());
		SourceRecord row = row(7, Map.of("address_id", "A-1", "sub_unit_number", "12", "null", "ignored"));

		EntityExtraction extraction = extractor.extract(row, mapping, List.of(parentRule, childRule));

		assertThat(extraction.failures()).isEmpty();
		assertThat(identity(extraction, "GeographicAddress")).isEqualTo("A-1");
		assertThat(identity(extraction, "GeographicSubAddress")).isEqualTo("7");
		assertThat(record(extraction, "GeographicSubAddress").fields())
				.containsEntry("sub_unit_number", "12")
				.doesNotContainKey("null");
	}

	@Test
	void extractsOneConfiguredEntity() {
		Model model = single("Invoice", "invoice_id", "invoice_number");
		SourceRecord row = row(4, Map.of("invoice_id", "I-9", "invoice_number", " 100 "));

		EntityExtraction extraction = extractor.extract(row, model.mapping(), model.rules());

		assertThat(extraction.failures()).isEmpty();
		assertThat(extraction.records()).singleElement().satisfies(record -> {
			assertThat(record.entityName()).isEqualTo("Invoice");
			assertThat(record.sourceRecordNumber()).isEqualTo(4L);
			assertThat(record.sourceIdentity()).isEqualTo("I-9");
			assertThat(record.fields()).containsEntry("invoice_id", "I-9").containsEntry("invoice_number", " 100 ");
		});
	}

	@Test
	void extractsEveryConfiguredEntityFromOnePhysicalRow() {
		Model model = hierarchy(
				entity("Customer", "customer_id", rule(1, "customer_id", "id"), rule(2, "customer_name", "name")),
				entity("Account", "account_id", rule(3, "account_id", "id"), rule(4, "account_number", "accountNumber")),
				entity("Subscription", "subscription_id", rule(5, "subscription_id", "id"), rule(6, "plan", "planCode")));
		SourceRecord row = row(10, Map.of(
				"customer_id", "C001",
				"customer_name", "John",
				"account_id", "A001",
				"account_number", "ACC-001",
				"subscription_id", "S001",
				"plan", "PREMIUM"));

		EntityExtraction extraction = extractor.extract(row, model.mapping(), model.rules());

		assertThat(extraction.failures()).isEmpty();
		assertThat(extraction.records()).extracting(EntityRecord::entityName)
				.containsExactly("Customer", "Account", "Subscription");
		assertThat(identity(extraction, "Customer")).isEqualTo("C001");
		assertThat(identity(extraction, "Account")).isEqualTo("A001");
		assertThat(identity(extraction, "Subscription")).isEqualTo("S001");
		assertThat(record(extraction, "Account").fields())
				.containsEntry("account_id", "A001")
				.containsEntry("account_number", "ACC-001")
				.doesNotContainKey("customer_name");
	}

	@Test
	void extractsProductHierarchyWithTheSameExtractor() {
		Model model = hierarchy(
				entity("Product", "product_id", rule(1, "product_id", "id"), rule(2, "product_name", "name")),
				entity("Variant", "variant_id", rule(3, "variant_id", "id"), rule(4, "sku", "sku")),
				entity("Price", "price_id", rule(5, "price_id", "id"), rule(6, "amount", "amount")));
		SourceRecord row = row(2, Map.of(
				"product_id", "P10",
				"product_name", "Widget",
				"variant_id", "V3",
				"sku", "SKU-1",
				"price_id", "PR1",
				"amount", "12.50"));

		EntityExtraction extraction = extractor.extract(row, model.mapping(), model.rules());

		assertThat(extraction.records()).extracting(EntityRecord::entityName, EntityRecord::sourceIdentity)
				.containsExactly(
						org.assertj.core.groups.Tuple.tuple("Product", "P10"),
						org.assertj.core.groups.Tuple.tuple("Variant", "V3"),
						org.assertj.core.groups.Tuple.tuple("Price", "PR1"));
	}

	@Test
	void extractsArbitraryEntityNames() {
		Model model = hierarchy(
				entity("Alpha", "alpha_code", rule(1, "alpha_code", "code"), rule(2, "alpha_label", "label")),
				entity("Beta", "beta_code", rule(3, "beta_code", "code"), rule(4, "beta_label", "label")),
				entity("Gamma", "gamma_code", rule(5, "gamma_code", "code"), rule(6, "gamma_label", "label")));
		SourceRecord row = row(1, Map.of(
				"alpha_code", "A",
				"alpha_label", "first",
				"beta_code", "B",
				"beta_label", "second",
				"gamma_code", "G",
				"gamma_label", "third"));

		EntityExtraction extraction = extractor.extract(row, model.mapping(), model.rules());

		assertThat(extraction.records()).extracting(EntityRecord::sourceIdentity).containsExactly("A", "B", "G");
		assertThat(record(extraction, "Beta").fields()).containsEntry("beta_code", "B").doesNotContainKey("alpha_label");
	}

	@Test
	void mapsEachEntityWithOnlyItsOwnRules() {
		Model model = hierarchy(
				entity("Customer", "customer_id", rule(1, "customer_id", "id"), rule(2, "customer_name", "name")),
				entity("Account", "account_id", rule(3, "account_id", "id"), rule(4, "account_number", "accountNumber")));
		SourceRecord row = row(1, Map.of(
				"customer_id", "C001",
				"customer_name", " John ",
				"account_id", "A001",
				"account_number", " ACC-001 "));
		EntityExtraction extraction = extractor.extract(row, model.mapping(), model.rules());

		assertThat(mapped(extraction, "Customer", model).fields())
				.containsExactlyInAnyOrderEntriesOf(Map.of("id", "C001", "name", "John"));
		assertThat(mapped(extraction, "Account", model).fields())
				.containsExactlyInAnyOrderEntriesOf(Map.of("id", "A001", "accountNumber", "ACC-001"));
	}

	@Test
	void readsIdentityFromTheConfiguredSourceKey() {
		Model model = single("Alpha", "alpha_code", "alpha_label");
		SourceRecord row = row(7, Map.of("alpha_code", "  AX  ", "alpha_label", "one", "id", "ignore-me"));

		EntityRecord record = extractor.extract(row, model.mapping(), model.rules()).records().get(0);

		assertThat(record.sourceIdentity()).isEqualTo("AX");
		assertThat(record.fields()).containsEntry("alpha_code", "  AX  ").doesNotContainKey("id");
	}

	@Test
	void rejectsEntityDataWhoseSourceKeyIsMissing() {
		Model model = hierarchy(
				entity("Customer", "customer_id", rule(1, "customer_id", "id"), rule(2, "customer_name", "name")),
				entity("Account", "account_id", rule(3, "account_id", "id"), rule(4, "account_number", "accountNumber")));
		SourceRecord row = row(105, Map.of(
				"customer_id", "C001",
				"customer_name", "John",
				"account_number", "ACC-001"));

		EntityExtraction extraction = extractor.extract(row, model.mapping(), model.rules());

		assertThat(extraction.records()).extracting(EntityRecord::entityName).containsExactly("Customer");
		assertThat(extraction.failures()).singleElement().satisfies(failure -> {
			assertThat(failure.entityName()).isEqualTo("Account");
			assertThat(failure.sourceKey()).isEqualTo("account_id");
			assertThat(failure.sourceRecordNumber()).isEqualTo(105L);
			assertThat(failure.message()).isEqualTo(
					"Unable to extract entity 'Account' from source record 105: source key 'account_id' is missing.");
		});
	}

	@Test
	void omitsEntityWhenItsIdentityAndFieldsAreBlank() {
		Model model = hierarchy(
				entity("Customer", "customer_id", rule(1, "customer_id", "id"), rule(2, "customer_name", "name")),
				entity("Account", "account_id", rule(3, "account_id", "id"), rule(4, "account_number", "accountNumber")));
		SourceRecord row = row(3, Map.of(
				"customer_id", "C001",
				"customer_name", "John",
				"account_id", "  ",
				"account_number", ""));

		EntityExtraction extraction = extractor.extract(row, model.mapping(), model.rules());

		assertThat(extraction.failures()).isEmpty();
		assertThat(extraction.records()).extracting(EntityRecord::entityName).containsExactly("Customer");
	}

	@Test
	void recognizesRepeatedParentIdentityWithoutRetainingPriorRows() {
		Model model = hierarchy(
				entity("Customer", "customer_id", rule(1, "customer_id", "id"), rule(2, "customer_name", "name")),
				entity("Account", "account_id", rule(3, "account_id", "id"), rule(4, "account_number", "accountNumber")));
		List<String> accounts = List.of("A001", "A002", "A003");

		for (int i = 0; i < accounts.size(); i++) {
			SourceRecord row = row(i + 1L, Map.of(
					"customer_id", "C001",
					"customer_name", "John",
					"account_id", accounts.get(i),
					"account_number", "ACC"));
			EntityExtraction extraction = extractor.extract(row, model.mapping(), model.rules());
			assertThat(identity(extraction, "Customer")).isEqualTo("C001");
			assertThat(identity(extraction, "Account")).isEqualTo(accounts.get(i));
		}
	}

	@Test
	void extractsEachStreamedRowWithoutAccumulatingTheFile() {
		Model model = single("Alpha", "alpha_code", "alpha_label");
		String lastIdentity = null;
		int rows = 0;
		for (int i = 1; i <= 200; i++) {
			EntityExtraction extraction = extractor.extract(
					row(i, Map.of("alpha_code", "A" + i, "alpha_label", "n")),
					model.mapping(),
					model.rules());
			lastIdentity = extraction.records().get(0).sourceIdentity();
			rows++;
		}
		assertThat(rows).isEqualTo(200);
		assertThat(lastIdentity).isEqualTo("A200");
	}

	@Test
	void csvConnectorStaysPhysicalAndExtractionFollows() throws Exception {
		Files.writeString(tempDir.resolve("mixed.csv"), """
				alpha_code,alpha_label,beta_code
				A,first,B
				""");
		Model model = hierarchy(
				entity("Alpha", "alpha_code", rule(1, "alpha_code", "code"), rule(2, "alpha_label", "label")),
				entity("Beta", "beta_code", rule(3, "beta_code", "code")));
		MigrationSource source = new MigrationSource();
		source.setSourceType("FILE");
		source.setConfiguration(Map.of(
				"provider", "FILE_SYSTEM",
				"objectKey", "mixed.csv",
				"fileFormat", "CSV",
				"delimiter", ",",
				"hasHeader", true,
				"encoding", "UTF-8"));
		MigrationBatch batch = new MigrationBatch();
		batch.setBatchNumber(1);
		batch.setSourceLocator(Map.of(
				"type", "CSV_ROW_RANGE",
				"objectKey", "mixed.csv",
				"startRecord", 1,
				"endRecord", 1));

		try (var read = csv.readBatch(source, batch)) {
			List<SourceRecord> physical = read.records().toList();
			assertThat(physical).singleElement().satisfies(record ->
					assertThat(record.fields()).containsEntry("alpha_code", "A").containsEntry("beta_code", "B"));
			EntityExtraction extraction = extractor.extract(physical.get(0), model.mapping(), model.rules());
			assertThat(extraction.records()).extracting(EntityRecord::entityName, EntityRecord::sourceIdentity)
					.containsExactly(
							org.assertj.core.groups.Tuple.tuple("Alpha", "A"),
							org.assertj.core.groups.Tuple.tuple("Beta", "B"));
		}
	}

	private com.etp.migration.processing.TargetRecord mapped(EntityExtraction extraction, String entityName, Model model) {
		EntityRecord record = record(extraction, entityName);
		return mappingEngine.mapEntity(record.toSourceRecord(), model.rules(), entityName);
	}

	private static EntityRecord record(EntityExtraction extraction, String entityName) {
		return extraction.records().stream()
				.filter(record -> record.entityName().equals(entityName))
				.findFirst()
				.orElseThrow();
	}

	private static String identity(EntityExtraction extraction, String entityName) {
		return record(extraction, entityName).sourceIdentity();
	}

	private static SourceRecord row(long number, Map<String, Object> fields) {
		return new SourceRecord(number, new LinkedHashMap<>(fields));
	}

	private static Model single(String name, String sourceKey, String attribute) {
		return hierarchy(entity(name, sourceKey, rule(1, sourceKey, "id"), rule(2, attribute, "value")));
	}

	private static Model hierarchy(ConfiguredEntity... entities) {
		List<EntityDefinition> definitions = new java.util.ArrayList<>();
		List<MigrationMappingRule> rules = new java.util.ArrayList<>();
		for (ConfiguredEntity entity : entities) {
			definitions.add(new EntityDefinition(entity.name(), entity.name().toLowerCase(), entity.sourceKey(), "id"));
			for (MigrationMappingRule rule : entity.rules()) {
				rule.setEntityName(entity.name());
				rules.add(rule);
			}
		}
		return new Model(new EntityRelationshipMapping(entities[0].name(), definitions, List.of()), List.copyOf(rules));
	}

	private static ConfiguredEntity entity(String name, String sourceKey, MigrationMappingRule... rules) {
		return new ConfiguredEntity(name, sourceKey, List.of(rules));
	}

	private static MigrationMappingRule constant(int sequence, String target, String value) {
		MigrationMappingRule rule = new MigrationMappingRule();
		rule.setSequenceNo(sequence);
		rule.setTargetField(target);
		rule.setTransformationType(TransformationType.DEFAULT);
		rule.setDefaultValue(value);
		rule.setEnabled(true);
		rule.setConfiguration(Map.of());
		return rule;
	}

	private static MigrationMappingRule rule(int sequence, String source, String target) {
		MigrationMappingRule rule = new MigrationMappingRule();
		rule.setSequenceNo(sequence);
		rule.setSourceField(source);
		rule.setTargetField(target);
		rule.setTransformationType(source.endsWith("_name") || source.endsWith("_number") || "amount".equals(source)
				? TransformationType.TRIM
				: TransformationType.DIRECT);
		rule.setEnabled(true);
		rule.setConfiguration(Map.of());
		return rule;
	}

	private record ConfiguredEntity(String name, String sourceKey, List<MigrationMappingRule> rules) {
	}

	private record Model(EntityRelationshipMapping mapping, List<MigrationMappingRule> rules) {
	}
}
