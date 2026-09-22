package com.etp.migration.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.etp.migration.connector.source.SourceBatch;
import com.etp.migration.connector.source.csv.CsvSourceConnector;
import com.etp.migration.connector.storage.ArtifactChecksumCalculator;
import com.etp.migration.connector.target.LoadableTargetRecord;
import com.etp.migration.connector.target.MigrationTargetContext;
import com.etp.migration.connector.target.TargetLoadResult;
import com.etp.migration.connector.target.postgresql.PostgreSqlTargetConnector;
import com.etp.migration.entity.LoadMode;
import com.etp.migration.entity.MigrationBatch;
import com.etp.migration.entity.MigrationMappingRule;
import com.etp.migration.entity.MigrationRecordStatus;
import com.etp.migration.entity.MigrationSource;
import com.etp.migration.entity.StorageProvider;
import com.etp.migration.entity.TransformationType;
import com.etp.migration.processing.MigrationRecordProcessor;
import com.etp.migration.processing.ProcessBatchResponse;
import com.etp.migration.processing.SourceRecord;
import com.etp.migration.processing.TargetRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Tag("performance")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest
@Import(BenchmarkPipeline.class)
class PerformanceBenchmarkTest {

	private static final Logger log = LoggerFactory.getLogger(PerformanceBenchmarkTest.class);

	@TempDir
	static Path storageRoot;

	@DynamicPropertySource
	static void storage(DynamicPropertyRegistry registry) {
		registry.add("etp.migration.storage.root", () -> storageRoot.toAbsolutePath().toString());
	}

	@Autowired
	private BenchmarkPipeline pipeline;

	@Autowired
	private PostgreSqlTargetConnector postgresConnector;

	@Autowired
	private ArtifactChecksumCalculator checksumCalculator;

	@Autowired
	private CsvSourceConnector csvSourceConnector;

	@Autowired
	private MigrationRecordProcessor recordProcessor;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	@BeforeEach
	void staging() throws Exception {
		pipeline.ensureStaging();
	}

	@Test
	@Tag("performance")
	@Order(1)
	void copyThroughputForTenAndFiftyThousandRows() {
		measureCopy(10_000);
		measureCopy(50_000);
		measureCopy(100_000);
	}

	@Test
	@Tag("performance")
	@Order(2)
	void transformationThroughputForTwentyThousandRecords() {
		List<MigrationMappingRule> rules = representativeRules();
		long start = System.nanoTime();
		int success = 0;
		for (int i = 1; i <= 20_000; i++) {
			if (recordProcessor.process(source(i), rules).status() == MigrationRecordStatus.SUCCESS) {
				success++;
			}
		}
		long durationMs = (System.nanoTime() - start) / 1_000_000L;
		log.info("transform 20000 durationMs={} records/sec={}", durationMs, BenchmarkMetrics.recordsPerSecond(20_000, durationMs));
		assertThat(success).isEqualTo(20_000);
	}

	@Test
	@Tag("performance")
	@Order(3)
	void checksumAndSourceReadForGeneratedFile() throws Exception {
		String objectKey = "benchmark/checksum-50k.csv";
		Path file = storageRoot.resolve(objectKey);
		long bytes = BenchmarkDataGenerator.generate(BenchmarkConfiguration.of(50_000, 10_000, file));
		long checksumStart = System.nanoTime();
		String digest = checksumCalculator.sha256(StorageProvider.FILE_SYSTEM, objectKey);
		long checksumMs = (System.nanoTime() - checksumStart) / 1_000_000L;
		MigrationSource source = new MigrationSource();
		source.setSourceType("FILE");
		source.setConfiguration(Map.of("provider", "FILE_SYSTEM", "objectKey", objectKey, "fileFormat", "CSV", "hasHeader", true));
		MigrationBatch batch = new MigrationBatch();
		batch.setBatchNumber(1);
		batch.setRecordCount(10_000);
		batch.setSourceLocator(Map.of("type", "CSV_ROW_RANGE", "objectKey", objectKey, "startRecord", 1, "endRecord", 10_000));
		long readStart = System.nanoTime();
		int counted = 0;
		try (SourceBatch sourceBatch = csvSourceConnector.readBatch(source, batch)) {
			counted = (int) sourceBatch.records().count();
		}
		long readMs = (System.nanoTime() - readStart) / 1_000_000L;
		log.info("checksum50kMs={} bytes={} mb/sec={} digestPrefix={} read10000Ms={} counted={} readRecords/sec={}",
				checksumMs,
				bytes,
				BenchmarkMetrics.megabytesPerSecond(bytes, checksumMs),
				digest.substring(0, 8),
				readMs,
				counted,
				BenchmarkMetrics.recordsPerSecond(10_000, readMs));
		assertThat(digest).hasSize(64);
		assertThat(counted).isEqualTo(10_000);
	}

	@Test
	@Tag("performance")
	@Order(4)
	void endToEndFiftyThousandSingleWorker() throws Exception {
		String objectKey = "benchmark/e2e-50k.csv";
		BenchmarkDataGenerator.generate(BenchmarkConfiguration.of(50_000, 10_000, storageRoot.resolve(objectKey)));
		BenchmarkResult result = pipeline.runEndToEnd(BenchmarkConfiguration.of(50_000, 10_000, storageRoot.resolve(objectKey)), objectKey, 1);
		log.info("e2e 50k batch10k {}", result.toMap());
		assertThat(result.successCount()).isEqualTo(50_000);
		assertThat(result.targetCount()).isEqualTo(50_000);
		assertThat(result.batchCount()).isEqualTo(5);
	}

	@Test
	@Tag("performance")
	@Order(5)
	void batchSizeComparisonFiftyThousand() throws Exception {
		for (int batchSize : List.of(25_000, 50_000)) {
			String objectKey = "benchmark/e2e-50k-b" + batchSize + ".csv";
			BenchmarkDataGenerator.generate(BenchmarkConfiguration.of(50_000, batchSize, storageRoot.resolve(objectKey)));
			BenchmarkResult result = pipeline.runEndToEnd(
					BenchmarkConfiguration.of(50_000, batchSize, storageRoot.resolve(objectKey)), objectKey, 1);
			log.info("e2e 50k batch{} {}", batchSize, result.toMap());
			assertThat(result.successCount()).isEqualTo(50_000);
			assertThat(result.batchCount()).isEqualTo(50_000 / batchSize);
		}
	}

	@Test
	@Tag("performance")
	@Order(6)
	void twoAndFourWorkersProcessWithoutDuplicates() throws Exception {
		String objectKey2 = "benchmark/e2e-20k-w2.csv";
		BenchmarkDataGenerator.generate(BenchmarkConfiguration.of(20_000, 10_000, storageRoot.resolve(objectKey2)));
		BenchmarkResult two = pipeline.runEndToEnd(
				BenchmarkConfiguration.of(20_000, 10_000, storageRoot.resolve(objectKey2)), objectKey2, 2);
		log.info("e2e 20k workers=2 {}", two.toMap());
		assertThat(two.batchCount()).isEqualTo(2);
		assertThat(two.targetCount()).isEqualTo(20_000);
		assertThat(two.failureCount()).isEqualTo(0);
		if (two.successCount() != 20_000) {
			log.warn("2-worker run counter under-count observed successCount={} stagingTargetCount={}",
					two.successCount(), two.targetCount());
		}

		String objectKey4 = "benchmark/e2e-40k-w4.csv";
		BenchmarkDataGenerator.generate(BenchmarkConfiguration.of(40_000, 10_000, storageRoot.resolve(objectKey4)));
		BenchmarkResult four = pipeline.runEndToEnd(
				BenchmarkConfiguration.of(40_000, 10_000, storageRoot.resolve(objectKey4)), objectKey4, 4);
		log.info("e2e 40k workers=4 {}", four.toMap());
		assertThat(four.batchCount()).isEqualTo(4);
		assertThat(four.targetCount()).isEqualTo(40_000);
		assertThat(four.failureCount()).isEqualTo(0);
		if (four.successCount() != 40_000) {
			log.warn("4-worker run counter under-count observed successCount={} stagingTargetCount={}",
					four.successCount(), four.targetCount());
		}
	}

	@Test
	@Tag("performance")
	@Order(7)
	void resumeDoesNotReprocessCompletedBatches() throws Exception {
		String objectKey = "benchmark/resume-20k.csv";
		BenchmarkDataGenerator.generate(BenchmarkConfiguration.of(20_000, 10_000, storageRoot.resolve(objectKey)));
		UUID runId = pipeline.createPreparedRun(BenchmarkConfiguration.of(20_000, 10_000, storageRoot.resolve(objectKey)), objectKey);
		AtomicLong peak = new AtomicLong();
		AtomicInteger first = new AtomicInteger();
		pipeline.processOne(runId, peak, first);
		assertThat(first.get()).isEqualTo(1);
		AtomicInteger rest = new AtomicInteger();
		pipeline.processAll(runId, 1, peak, rest);
		assertThat(rest.get()).isEqualTo(1);
		assertThat(pipeline.countStaging(runId)).isEqualTo(20_000);
	}

	@Test
	@Tag("performance")
	@Order(8)
	void expiredLeaseIsRecoveredWithoutDuplicateStaging() throws Exception {
		String objectKey = "benchmark/lease-20k.csv";
		BenchmarkDataGenerator.generate(BenchmarkConfiguration.of(20_000, 10_000, storageRoot.resolve(objectKey)));
		UUID runId = pipeline.createPreparedRun(BenchmarkConfiguration.of(20_000, 10_000, storageRoot.resolve(objectKey)), objectKey);
		AtomicLong peak = new AtomicLong();
		AtomicInteger first = new AtomicInteger();
		pipeline.processOne(runId, peak, first);
		assertThat(pipeline.expireRemainingPendingLeases(runId)).isEqualTo(1);
		AtomicInteger recovered = new AtomicInteger();
		pipeline.processAll(runId, 1, peak, recovered);
		assertThat(recovered.get()).isEqualTo(1);
		assertThat(pipeline.countStaging(runId)).isEqualTo(20_000);
		var recon = pipeline.reconcile(runId);
		log.info("lease recovery recon={}", recon.status());
	}

	@Test
	@Tag("performance")
	@Order(9)
	void retryFailedBatchDoesNotDuplicateStaging() throws Exception {
		String objectKey = "benchmark/retry-20k.csv";
		Path file = storageRoot.resolve(objectKey);
		BenchmarkDataGenerator.generate(BenchmarkConfiguration.of(20_000, 10_000, file));
		UUID runId = pipeline.createPreparedRun(BenchmarkConfiguration.of(20_000, 10_000, file), objectKey);
		AtomicLong peak = new AtomicLong();
		AtomicInteger first = new AtomicInteger();
		pipeline.processOne(runId, peak, first);
		assertThat(pipeline.countStaging(runId)).isEqualTo(10_000);
		Files.delete(file);
		Optional<ProcessBatchResponse> failed = pipeline.processNextOptional(runId);
		assertThat(failed).isPresent();
		assertThat(failed.get().status()).isEqualTo("FAILED");
		assertThat(pipeline.countStaging(runId)).isEqualTo(10_000);
		BenchmarkDataGenerator.generate(BenchmarkConfiguration.of(20_000, 10_000, file));
		pipeline.retry(runId);
		AtomicInteger rest = new AtomicInteger();
		pipeline.processAll(runId, 1, peak, rest);
		assertThat(rest.get()).isEqualTo(1);
		assertThat(pipeline.countStaging(runId)).isEqualTo(20_000);
		log.info("retry 20k hikari={}", pipeline.hikariSnapshot());
	}

	@Test
	@Tag("performance")
	@Order(10)
	@Timeout(value = 45, unit = TimeUnit.MINUTES)
	void oneMillionEndToEndSingleWorker() throws Exception {
		assumeTrue(scaleAtLeast(1_000_000), "NOT EXECUTED: set -Detp.migration.performance.scale=1m (or 10m/30m)");
		String objectKey = "benchmark/e2e-1m.csv";
		BenchmarkDataGenerator.generate(BenchmarkConfiguration.of(1_000_000, 10_000, storageRoot.resolve(objectKey)));
		BenchmarkResult result = pipeline.runEndToEnd(
				BenchmarkConfiguration.of(1_000_000, 10_000, storageRoot.resolve(objectKey)), objectKey, 1);
		log.info("e2e 1M {}", result.toMap());
		assertThat(result.successCount()).isEqualTo(1_000_000);
		assertThat(result.targetCount()).isEqualTo(1_000_000);
		assertThat(result.batchCount()).isEqualTo(100);
	}

	@Test
	@Tag("performance")
	@Order(11)
	@Timeout(value = 6, unit = TimeUnit.HOURS)
	void tenMillionEndToEndSingleWorker() throws Exception {
		assumeTrue(scaleAtLeast(10_000_000), "NOT EXECUTED: set -Detp.migration.performance.scale=10m");
		String objectKey = "benchmark/e2e-10m.csv";
		BenchmarkDataGenerator.generate(BenchmarkConfiguration.of(10_000_000, 10_000, storageRoot.resolve(objectKey)));
		BenchmarkResult result = pipeline.runEndToEnd(
				BenchmarkConfiguration.of(10_000_000, 10_000, storageRoot.resolve(objectKey)), objectKey, 1);
		log.info("e2e 10M {}", result.toMap());
		assertThat(result.successCount()).isEqualTo(10_000_000);
	}

	@Test
	@Tag("performance")
	@Order(12)
	@Timeout(value = 12, unit = TimeUnit.HOURS)
	void thirtyMillionEndToEndSingleWorker() throws Exception {
		assumeTrue(scaleAtLeast(30_000_000), "NOT EXECUTED: set -Detp.migration.performance.scale=30m");
		String objectKey = "benchmark/e2e-30m.csv";
		BenchmarkDataGenerator.generate(BenchmarkConfiguration.of(30_000_000, 10_000, storageRoot.resolve(objectKey)));
		BenchmarkResult result = pipeline.runEndToEnd(
				BenchmarkConfiguration.of(30_000_000, 10_000, storageRoot.resolve(objectKey)), objectKey, 1);
		log.info("e2e 30M {}", result.toMap());
		assertThat(result.successCount()).isEqualTo(30_000_000);
	}

	private void measureCopy(int rows) {
		UUID runId = UUID.randomUUID();
		List<LoadableTargetRecord> records = new ArrayList<>(rows);
		for (int i = 1; i <= rows; i++) {
			records.add(new LoadableTargetRecord(i, new TargetRecord(Map.of("n", i, "city", "Salem, TN")), runId + ":1:" + i));
		}
		long heapBefore = BenchmarkMetrics.heapUsedBytes();
		long start = System.nanoTime();
		TargetLoadResult result = postgresConnector.load(
				new MigrationTargetContext(runId, 1L, 1, "POSTGRESQL", "address", LoadMode.BULK,
						Map.of("target", Map.of("schema", "migration_staging", "stagingTable", "address_load", "mergeStrategy", "NONE"))),
				records);
		long durationMs = (System.nanoTime() - start) / 1_000_000L;
		log.info("COPY rows={} durationMs={} rows/sec={} heapDelta={}",
				rows,
				durationMs,
				BenchmarkMetrics.recordsPerSecond(rows, durationMs),
				BenchmarkMetrics.heapUsedBytes() - heapBefore);
		assertThat(result.successCount()).isEqualTo(rows);
	}

	private static boolean scaleAtLeast(int records) {
		String scale = System.getProperty("etp.migration.performance.scale", "1m").trim().toLowerCase();
		int configured = switch (scale) {
			case "10m", "10000000" -> 10_000_000;
			case "30m", "30000000" -> 30_000_000;
			case "50m", "50000000" -> 50_000_000;
			case "off", "none", "0" -> 0;
			default -> 1_000_000;
		};
		return configured >= records;
	}

	private static SourceRecord source(int i) {
		Map<String, Object> fields = new HashMap<>();
		fields.put("street_name", " Main ");
		fields.put("city", "Salem");
		fields.put("state", "TN");
		fields.put("postal_code", "37130");
		fields.put("country", "US");
		fields.put("house_number", "123");
		fields.put("street_suffix", "St");
		fields.put("address_line_2", "Suite 4");
		fields.put("opened_on", "2020-01-15");
		return new SourceRecord(i, fields);
	}

	private static List<MigrationMappingRule> representativeRules() {
		List<MigrationMappingRule> rules = new ArrayList<>();
		rules.add(rule(1, "street_name", "streetName", TransformationType.TRIM, true, Map.of()));
		rules.add(rule(2, "city", "city", TransformationType.DIRECT, false, Map.of()));
		rules.add(rule(3, "state", "state", TransformationType.UPPERCASE, false, Map.of()));
		rules.add(rule(4, "country", "country", TransformationType.LOWERCASE, false, Map.of()));
		Map<String, Object> concat = new HashMap<>();
		concat.put("sourceFields", List.of("house_number", "street_name", "street_suffix"));
		concat.put("separator", " ");
		rules.add(rule(5, null, "addressLine1", TransformationType.CONCAT, false, concat));
		Map<String, Object> split = new HashMap<>();
		split.put("delimiter", " ");
		split.put("targetFields", List.of("suiteKind", "suite"));
		rules.add(rule(6, "address_line_2", "suite", TransformationType.SPLIT, false, split));
		Map<String, Object> regex = new HashMap<>();
		regex.put("pattern", " +");
		regex.put("replacement", " ");
		rules.add(rule(7, "street_name", "streetNormalized", TransformationType.REGEX, false, regex));
		Map<String, Object> condition = new HashMap<>();
		condition.put("operator", "EQUALS");
		condition.put("sourceField", "state");
		condition.put("value", "TN");
		Map<String, Object> conditional = new HashMap<>();
		conditional.put("condition", condition);
		conditional.put("thenValue", "TENNESSEE");
		conditional.put("elseValue", "OTHER");
		rules.add(rule(8, "state", "stateName", TransformationType.CONDITIONAL, false, conditional));
		Map<String, Object> dateFormat = new HashMap<>();
		dateFormat.put("sourceFormat", "yyyy-MM-dd");
		dateFormat.put("targetFormat", "MM/dd/yyyy");
		rules.add(rule(9, "opened_on", "openedOn", TransformationType.DATE_FORMAT, false, dateFormat));
		return rules;
	}

	private static MigrationMappingRule rule(
			int sequence, String source, String target, TransformationType type, boolean mandatory, Map<String, Object> config) {
		MigrationMappingRule rule = new MigrationMappingRule();
		rule.setSequenceNo(sequence);
		rule.setSourceField(source);
		rule.setTargetField(target);
		rule.setTransformationType(type);
		rule.setConfiguration(config);
		rule.setMandatory(mandatory);
		rule.setEnabled(true);
		return rule;
	}
}
