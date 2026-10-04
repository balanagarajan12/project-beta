package com.etp.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.etp.migration.batch.BatchPlanner.BatchPlanRequest;
import com.etp.migration.batch.BatchPlanner.PlannedBatch;
import com.etp.migration.batch.RecordCountBatchPlanner;
import com.etp.migration.connector.source.SourceInspection;
import com.etp.migration.connector.source.csv.CsvSourceConnector;
import com.etp.migration.connector.storage.DefaultArtifactStorage;
import com.etp.migration.connector.storage.FileSystemArtifactStorage;
import com.etp.migration.connector.target.postgresql.PromotionTargetConfiguration;
import com.etp.migration.entity.MigrationBatch;
import com.etp.migration.entity.MigrationMappingRule;
import com.etp.migration.entity.MigrationRecordStatus;
import com.etp.migration.entity.MigrationRun;
import com.etp.migration.entity.MigrationSource;
import com.etp.migration.entity.PromotionStrategy;
import com.etp.migration.entity.TransformationType;
import com.etp.migration.processing.MigrationRecordProcessor;
import com.etp.migration.processing.ProcessedRecord;
import com.etp.migration.processing.SourceRecord;
import com.etp.migration.transformation.MappingEngine;
import com.etp.migration.transformation.TransformationEngine;
import com.etp.migration.validation.ValidationEngine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Phase 0 characterization: the current pipeline treats entity and field names as configuration.
 * Customer and Product are unrelated examples. Neither name is known to the engine.
 */
class DomainNeutralPipelineTest {

	private static final int BATCH_SIZE = 10_000;

	private final MigrationRecordProcessor processor = new MigrationRecordProcessor(
			new MappingEngine(new TransformationEngine()),
			new ValidationEngine());
	private final RecordCountBatchPlanner planner = new RecordCountBatchPlanner();

	@TempDir
	Path tempDir;

	private CsvSourceConnector csv;

	@BeforeEach
	void setUp() {
		FileSystemArtifactStorage fileSystem = new FileSystemArtifactStorage(tempDir);
		fileSystem.initializeRoot();
		csv = new CsvSourceConnector(new DefaultArtifactStorage(fileSystem));
	}

	@ParameterizedTest
	@MethodSource("models")
	void fieldPipelineFollowsConfiguredRules(Model model) {
		ProcessedRecord result = processor.process(model.sourceRecord(), model.rules());

		assertThat(result.status()).isEqualTo(MigrationRecordStatus.SUCCESS);
		assertThat(result.target().fields()).containsExactlyInAnyOrderEntriesOf(model.expectedFields());
	}

	@ParameterizedTest
	@MethodSource("models")
	void csvConnectorExposesColumnsWithoutInterpretingEntities(Model model) throws Exception {
		Files.writeString(tempDir.resolve(model.fileName()), model.csv());

		SourceInspection inspection = csv.inspect(source(model.fileName()), new MigrationRun());

		assertThat(inspection.columns()).extracting("name").containsExactlyElementsOf(model.headers());
		assertThat(inspection.recordCount()).isEqualTo(model.rowCount());

		try (var batch = csv.readBatch(source(model.fileName()), batch(model.fileName(), 1, model.rowCount()))) {
			List<ProcessedRecord> processed = batch.records()
					.map(record -> processor.process(record, model.rules()))
					.toList();
			assertThat(processed).allSatisfy(record -> {
				assertThat(record.status()).isEqualTo(MigrationRecordStatus.SUCCESS);
				assertThat(record.target().fields()).containsExactlyInAnyOrderEntriesOf(model.expectedFields());
			});
		}
	}

	@Test
	void oneProcessorHandlesBothModelsWithoutDomainBranches() {
		List<Model> models = models().toList();
		assertThat(models).extracting(Model::label).containsExactly("customer-account-subscription", "product-variant-price");

		Map<String, Map<String, Object>> payloads = new LinkedHashMap<>();
		for (Model model : models) {
			ProcessedRecord result = processor.process(model.sourceRecord(), model.rules());
			payloads.put(model.targetEntity(), result.target().fields());
		}

		assertThat(payloads.get("customer")).containsEntry("customerId", "C001");
		assertThat(payloads.get("product")).containsEntry("productId", "P10");
		assertThat(payloads.get("customer")).doesNotContainKey("productId");
		assertThat(payloads.get("product")).doesNotContainKey("customerId");
	}

	@Test
	void rulesForOneModelDoNotRecognizeAnotherModelsRow() {
		Model customer = customerModel();
		Model product = productModel();

		ProcessedRecord customerRulesOnProduct = processor.process(product.sourceRecord(), customer.rules());
		ProcessedRecord productRulesOnCustomer = processor.process(customer.sourceRecord(), product.rules());

		assertThat(customerRulesOnProduct.status()).isEqualTo(MigrationRecordStatus.VALIDATION_FAILED);
		assertThat(customerRulesOnProduct.target().fields()).doesNotContainValue("Widget");
		assertThat(productRulesOnCustomer.status()).isEqualTo(MigrationRecordStatus.VALIDATION_FAILED);
		assertThat(productRulesOnCustomer.target().fields()).doesNotContainValue("Ada Lovelace");
	}

	@Test
	void batchBoundariesFollowRecordCountForEitherModel() {
		List<PlannedBatch> customerPlan = planner.plan(new MigrationRun(), new BatchPlanRequest(25_000, BATCH_SIZE));
		List<PlannedBatch> productPlan = planner.plan(new MigrationRun(), new BatchPlanRequest(25_000, BATCH_SIZE));

		assertThat(customerPlan).isEqualTo(productPlan);
		assertThat(customerPlan).extracting(PlannedBatch::startOffset).containsExactly(0L, 10_000L, 20_000L);
		assertThat(customerPlan).extracting(PlannedBatch::recordCount).containsExactly(10_000, 10_000, 5_000);
		assertThat(customerPlan).allSatisfy(batch -> assertThat(batch.sourceLocator().keySet())
				.containsExactlyInAnyOrder("startOffset", "endOffset", "recordCount"));
	}

	@ParameterizedTest
	@MethodSource("models")
	void promotionIdentifiersComeFromConfiguration(Model model) {
		PromotionTargetConfiguration parsed = PromotionTargetConfiguration.parse(model.promotionConfiguration());

		assertThat(parsed.schema()).isEqualTo(model.schema());
		assertThat(parsed.table()).isEqualTo(model.table());
		assertThat(parsed.strategy()).isEqualTo(PromotionStrategy.UPSERT);
		assertThat(parsed.conflictKeys()).containsExactly(model.conflictKey());
		assertThat(parsed.columns()).containsEntry(model.conflictKey(), model.conflictKey());
	}

	@Test
	void engineSourcesDoNotHardcodeDomainEntities() throws IOException {
		Path module = moduleRoot();
		List<Path> roots = List.of(
				module.resolve("src/main/java/com/etp/migration/batch"),
				module.resolve("src/main/java/com/etp/migration/processing"),
				module.resolve("src/main/java/com/etp/migration/transformation"),
				module.resolve("src/main/java/com/etp/migration/validation"),
				module.resolve("src/main/java/com/etp/migration/reconciliation"),
				module.resolve("src/main/java/com/etp/migration/connector/source"),
				module.resolve("src/main/java/com/etp/migration/run"),
				module.resolve("src/main/java/com/etp/migration/migration"),
				module.resolve("src/main/java/com/etp/migration/promotion"),
				module.resolve("src/main/java/com/etp/migration/connector/target/postgresql"),
				module.resolve("src/main/java/com/etp/migration/mapping"),
				module.resolve("src/main/java/com/etp/migration/extraction"),
				module.resolve("src/main/java/com/etp/migration/relationship"),
				module.resolve("src/main/java/com/etp/migration/scope"),
				module.resolve("src/main/java/com/etp/migration/planning"),
				module.resolve("src/main/java/com/etp/migration/identity"),
				module.resolve("src/main/java/com/etp/migration/execution"),
				module.resolve("src/main/java/com/etp/migration/performance"));
		Pattern forbidden = Pattern.compile(
				"\"(Customer|Account|Subscription|Product|Variant|Price|Order|OrderItem|Location|Address|Site|GeographicLocation|GeographicAddress|GeographicSite|UAM|Telecom|Fiber)\"");

		List<String> hits = new ArrayList<>();
		for (Path root : roots) {
			assertThat(root).exists();
			try (Stream<Path> files = Files.walk(root)) {
				files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
					try {
						String source = Files.readString(path);
						if (forbidden.matcher(source).find()) {
							hits.add(module.relativize(path).toString());
						}
					} catch (IOException ex) {
						throw new IllegalStateException(ex);
					}
				});
			}
		}
		assertThat(hits).isEmpty();
	}

	static Stream<Model> models() {
		return Stream.of(customerModel(), productModel());
	}

	private static Model customerModel() {
		return new Model(
				"customer-account-subscription",
				"customer",
				"sales",
				"customer",
				"customer_id",
				"customers.csv",
				List.of("customer_id", "customer_name", "account_id", "subscription_plan"),
				"C001,  Ada Lovelace ,A100, gold ",
				List.of(
						rule(1, "customer_id", "customerId", TransformationType.DIRECT, true),
						rule(2, "customer_name", "name", TransformationType.TRIM, true),
						rule(3, "account_id", "accountId", TransformationType.DIRECT, true),
						rule(4, "subscription_plan", "planCode", TransformationType.UPPERCASE, true)),
				Map.of(
						"customerId", "C001",
						"name", "Ada Lovelace",
						"accountId", "A100",
						"planCode", " GOLD "));
	}

	private static Model productModel() {
		return new Model(
				"product-variant-price",
				"product",
				"catalog",
				"product",
				"product_id",
				"products.csv",
				List.of("product_id", "product_name", "variant_sku", "price_code"),
				"P10,  Widget ,sku-1, list ",
				List.of(
						rule(1, "product_id", "productId", TransformationType.DIRECT, true),
						rule(2, "product_name", "name", TransformationType.TRIM, true),
						rule(3, "variant_sku", "sku", TransformationType.UPPERCASE, true),
						rule(4, "price_code", "priceCode", TransformationType.TRIM, true)),
				Map.of(
						"productId", "P10",
						"name", "Widget",
						"sku", "SKU-1",
						"priceCode", "list"));
	}

	private static MigrationMappingRule rule(
			int sequence,
			String sourceField,
			String targetField,
			TransformationType type,
			boolean mandatory) {
		MigrationMappingRule rule = new MigrationMappingRule();
		rule.setSequenceNo(sequence);
		rule.setSourceField(sourceField);
		rule.setTargetField(targetField);
		rule.setTransformationType(type);
		rule.setEnabled(true);
		rule.setMandatory(mandatory);
		rule.setConfiguration(Map.of());
		return rule;
	}

	private MigrationSource source(String objectKey) {
		MigrationSource source = new MigrationSource();
		source.setSourceType("FILE");
		Map<String, Object> configuration = new LinkedHashMap<>();
		configuration.put("provider", "FILE_SYSTEM");
		configuration.put("objectKey", objectKey);
		configuration.put("fileFormat", "CSV");
		configuration.put("delimiter", ",");
		configuration.put("hasHeader", true);
		configuration.put("encoding", "UTF-8");
		source.setConfiguration(configuration);
		return source;
	}

	private static MigrationBatch batch(String objectKey, long start, long end) {
		MigrationBatch batch = new MigrationBatch();
		batch.setBatchNumber(1);
		batch.setSourceLocator(Map.of(
				"type", "CSV_ROW_RANGE",
				"objectKey", objectKey,
				"startRecord", start,
				"endRecord", end));
		return batch;
	}

	private static Path moduleRoot() {
		Path dir = Path.of("").toAbsolutePath();
		while (dir != null) {
			Path pom = dir.resolve("pom.xml");
			if (Files.exists(pom)) {
				try {
					if (Files.readString(pom).contains("<artifactId>etp-migration</artifactId>")) {
						return dir;
					}
				} catch (IOException ex) {
					throw new IllegalStateException(ex);
				}
			}
			dir = dir.getParent();
		}
		throw new IllegalStateException("etp-migration module root was not found");
	}

	record Model(
			String label,
			String targetEntity,
			String schema,
			String table,
			String conflictKey,
			String fileName,
			List<String> headers,
			String dataRow,
			List<MigrationMappingRule> rules,
			Map<String, Object> expectedFields) {

		SourceRecord sourceRecord() {
			Map<String, Object> fields = new LinkedHashMap<>();
			String[] values = dataRow.split(",", -1);
			for (int i = 0; i < headers.size(); i++) {
				fields.put(headers.get(i), values[i]);
			}
			return new SourceRecord(1L, fields);
		}

		String csv() {
			return String.join(",", headers) + "\n" + dataRow + "\n" + dataRow + "\n";
		}

		long rowCount() {
			return 2L;
		}

		Map<String, Object> promotionConfiguration() {
			Map<String, String> columns = new LinkedHashMap<>();
			columns.put(conflictKey, conflictKey);
			columns.put("name", "name");
			return Map.of(
					"promotion",
					Map.of(
							"enabled", true,
							"schema", schema,
							"table", table,
							"strategy", "UPSERT",
							"columns", columns,
							"conflictKeys", List.of(conflictKey)));
		}
	}
}
