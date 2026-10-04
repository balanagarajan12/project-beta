package com.etp.migration.performance;

import static org.assertj.core.api.Assertions.assertThat;

import com.etp.migration.connector.source.csv.CsvSourceConnector;
import com.etp.migration.connector.source.csv.MultiFileEntitySource;
import com.etp.migration.connector.storage.DefaultArtifactStorage;
import com.etp.migration.connector.storage.FileSystemArtifactStorage;
import com.etp.migration.entity.MigrationMappingRule;
import com.etp.migration.entity.TransformationType;
import com.etp.migration.connector.target.LoadableTargetRecord;
import com.etp.migration.connector.target.MigrationTargetConnector;
import com.etp.migration.connector.target.MigrationTargetConnectorRegistry;
import com.etp.migration.connector.target.TargetLoadResult;
import com.etp.migration.entity.LoadMode;
import com.etp.migration.execution.EntityBatchLocator;
import com.etp.migration.execution.EntityCsvAccess;
import com.etp.migration.execution.EntityRelationshipBatchProcessor;
import com.etp.migration.execution.EntityRelationshipPlanSource;
import com.etp.migration.execution.EntityTargetComposer;
import com.etp.migration.execution.StoredEntityBatch;
import com.etp.migration.extraction.EntityExtractor;
import com.etp.migration.identity.TargetIdentityLookup;
import com.etp.migration.mapping.EntityDefinition;
import com.etp.migration.mapping.EntityRelationshipMapping;
import com.etp.migration.mapping.RelationshipCardinality;
import com.etp.migration.mapping.RelationshipDefinition;
import com.etp.migration.mapping.RelationshipKey;
import com.etp.migration.planning.ExecutionBatchPlan;
import com.etp.migration.planning.ExecutionPlanner;
import com.etp.migration.processing.PipelineTiming;
import com.etp.migration.processing.SourceRecord;
import com.etp.migration.reconciliation.EntityReconciliationAccumulator;
import com.etp.migration.relationship.RelationshipResolver;
import com.etp.migration.scope.LogicalMigrationScopeBuilder;
import com.etp.migration.transformation.MappingEngine;
import com.etp.migration.transformation.TransformationEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.etp.migration.validation.ValidationEngine;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Local benchmark measurement of CSV prepare. This is not a production-capacity claim.
 * Reconciliation aggregation is timed separately from prepare.
 */
@Tag("performance")
class EntityPipelineBenchmarkTest {

	private static final int BATCH_SIZE = 10_000;
	private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

	@TempDir
	Path tempDir;

	@AfterEach
	void releaseTiming() {
		PipelineTiming.bind(null);
	}

	@Test
	void measuresFlatAndEntityLayouts() throws Exception {
		List<Map<String, Object>> results = new ArrayList<>();
		results.add(flatRead(10_000).toMap());
		results.add(flatRead(100_000).toMap());
		results.add(entityPrepare("ENTITY_RELATIONSHIP_SINGLE_FILE", 10_000, false).toMap());
		results.add(entityPrepare("ENTITY_RELATIONSHIP_MULTI_FILE", 10_000, true).toMap());
		results.add(entityPrepare("ENTITY_RELATIONSHIP_SINGLE_FILE", 100_000, false).toMap());
		results.add(entityPrepare("ENTITY_RELATIONSHIP_MULTI_FILE", 100_000, true).toMap());
		if ("1m".equalsIgnoreCase(System.getProperty("etp.migration.performance.entity-scale",
				System.getenv("ETP_MIGRATION_ENTITY_SCALE")))) {
			results.add(entityPrepare("ENTITY_RELATIONSHIP_SINGLE_FILE", 1_000_000, false).toMap());
			results.add(entityPrepare("ENTITY_RELATIONSHIP_MULTI_FILE", 1_000_000, true).toMap());
		}
		System.out.println("PHASE10_LOCAL_BENCHMARK " + JSON.writeValueAsString(results));
		assertThat(results).allSatisfy(result -> {
			assertThat(result.get("status")).isEqualTo("MEASURED");
			assertThat(((Number) result.get("batchCount")).intValue()).isPositive();
			assertThat(((Number) result.get("recordsPerSecond")).doubleValue()).isPositive();
		});
	}

	private BenchmarkResult flatRead(int records) {
		Path file = tempDir.resolve("flat-" + records + ".csv");
		Instant started = Instant.now();
		long heapBefore = BenchmarkMetrics.heapUsedBytes();
		BenchmarkDataGenerator.generate(BenchmarkConfiguration.of(records, BATCH_SIZE, file));
		CsvSourceConnector csv = csv();
		long readStarted = System.nanoTime();
		long counted;
		try (Stream<SourceRecord> rows = csv.readAll(com.etp.migration.connector.source.csv.CsvFileSourceConfiguration.from(Map.of(
				"provider", "FILE_SYSTEM",
				"fileFormat", "CSV",
				"hasHeader", true,
				"objectKey", file.getFileName().toString())))) {
			counted = rows.count();
		}
		long elapsed = Math.max(1L, (System.nanoTime() - readStarted) / 1_000_000L);
		Map<String, Object> extras = new LinkedHashMap<>();
		extras.put("scenario", "FLAT_CSV_SOURCE_READ");
		extras.put("layout", "FLAT");
		extras.put("measurement", "local benchmark measurement");
		extras.put("target", "none");
		extras.put("note", "Source read only. Staging load is not included.");
		return new BenchmarkResult(
				"FLAT_CSV_SOURCE_READ",
				records,
				BATCH_SIZE,
				1,
				1,
				elapsed,
				BenchmarkMetrics.recordsPerSecond(counted, elapsed),
				counted,
				0,
				null,
				0,
				0,
				0,
				Math.max(heapBefore, BenchmarkMetrics.heapUsedBytes()),
				BenchmarkMetrics.heapUsedBytes(),
				"MEASURED",
				started,
				Instant.now(),
				extras);
	}

	private BenchmarkResult entityPrepare(String scenario, int leaves, boolean multiFile) throws Exception {
		String folder = "multi-" + leaves;
		Path output = multiFile ? tempDir.resolve(folder) : tempDir.resolve("single-" + leaves + ".csv");
		EntityDatasetGenerator.Shape shape = multiFile
				? EntityDatasetGenerator.writeMulti(output, leaves)
				: EntityDatasetGenerator.writeSingle(output, leaves);
		Map<String, Object> source = multiFile ? multiLayout(folder) : singleLayout(output.getFileName().toString());
		PipelineTiming timing = new PipelineTiming();
		PipelineTiming.bind(timing);
		EntityReconciliationAccumulator reconciliation = new EntityReconciliationAccumulator();
		long[] relationships = {0L};
		long[] entities = {0L};
		int[] batches = {0};
		long[] reconciliationNanos = {0L};
		List<ExecutionBatchPlan> captured = leaves <= 100_000 ? new ArrayList<>() : null;
		Instant started = Instant.now();
		long heapBefore = BenchmarkMetrics.heapUsedBytes();
		long startedNanos = System.nanoTime();
		planSource().consume(source, mapping(), rules(), BATCH_SIZE, batch -> {
			assertThat(batch.workItems()).hasSizeLessThanOrEqualTo(BATCH_SIZE);
			batches[0]++;
			entities[0] += batch.workItems().size();
			if (captured != null) {
				captured.add(batch);
			}
			long reconciliationStarted = System.nanoTime();
			reconciliation.beginBatch();
			for (var item : batch.workItems()) {
				List<String> parents = item.parents().stream().map(parent -> parent.entityName()).toList();
				reconciliation.notePlanned(item.entityName(), parents);
				relationships[0] += parents.size();
			}
			reconciliationNanos[0] += System.nanoTime() - reconciliationStarted;
		}, failure -> {
			throw new IllegalStateException(failure.message());
		});
		long reconciliationFinish = System.nanoTime();
		reconciliation.finish();
		reconciliationNanos[0] += System.nanoTime() - reconciliationFinish;
		long reconciliationMs = reconciliationNanos[0] / 1_000_000L;
		long elapsed = Math.max(1L, ((System.nanoTime() - startedNanos) - reconciliationNanos[0]) / 1_000_000L);
		assertThat(entities[0]).isEqualTo(shape.entities());
		Map<String, Object> extras = new LinkedHashMap<>();
		extras.put("scenario", scenario);
		extras.put("layout", multiFile ? "MULTI_FILE" : "SINGLE_FILE");
		extras.put("measurement", "local benchmark measurement");
		extras.put("target", "prepare-only; staging load is not included");
		extras.put("leaves", leaves);
		extras.put("entities", Map.of("EntityA", shape.roots(), "EntityB", shape.middles(), "EntityC", shape.leaves()));
		extras.put("relationshipCount", relationships[0]);
		extras.put("entitiesPerSecond", BenchmarkMetrics.recordsPerSecond(entities[0], elapsed));
		extras.put("observedHeapBytes", Math.max(heapBefore, BenchmarkMetrics.heapUsedBytes()));
		extras.put("sourceFileOpens", timing.counter("sourceFileOpens"));
		extras.put("scopeIdentityCount", timing.counter("scopeIdentityCount"));
		extras.put("scopeIndexBytes", timing.counter("scopeIndexBytes"));
		extras.put("maxScopeMembers", timing.counter("maxScopeMembers"));
		extras.put("relationshipIndexedParents", timing.counter("relationshipIndexedParents"));
		extras.put("relationshipIndexBytes", timing.counter("relationshipIndexBytes"));
		extras.put("stageMillis", timing.toMap());
		extras.put("processNote", captured == null
				? "1M row process-batch was not run. elapsedMs is prepare only. Each later batch would re-read the source."
				: "processMs re-reads the source once per batch into a recording target. It is not included in elapsedMs and is not PostgreSQL staging.");
		if (captured != null) {
			PipelineTiming processTiming = new PipelineTiming();
			PipelineTiming.bind(processTiming);
			long processStarted = System.nanoTime();
			EntityRelationshipBatchProcessor processor = processor();
			Map<String, Object> mappingConfiguration = com.etp.migration.mapping.EntityRelationshipConfiguration.toConfiguration(mapping());
			UUID runId = UUID.randomUUID();
			long loaded = 0;
			for (ExecutionBatchPlan batch : captured) {
				var outcome = processor.process(new StoredEntityBatch(
						(long) batch.batchNumber(),
						batch.batchNumber(),
						batch.workItems().size(),
						runId,
						source,
						mappingConfiguration,
						rules(),
						EntityBatchLocator.write(batch),
						"STAGING",
						"entity_a",
						LoadMode.BULK,
						Map.of()));
				assertThat(outcome.failures()).isEmpty();
				loaded += outcome.counters().successCount();
			}
			long processMs = Math.max(1L, (System.nanoTime() - processStarted) / 1_000_000L);
			assertThat(loaded).isEqualTo(entities[0]);
			extras.put("processMs", processMs);
			extras.put("processEntitiesPerSecond", BenchmarkMetrics.recordsPerSecond(loaded, processMs));
			extras.put("identityLookupCalls", processTiming.counter("identityLookupCalls"));
			PipelineTiming.bind(timing);
		}
		assertThat(timing.counter("sourceFileOpens")).isLessThanOrEqualTo(3L * 3L);
		return new BenchmarkResult(
				scenario,
				leaves,
				BATCH_SIZE,
				1,
				batches[0],
				elapsed,
				BenchmarkMetrics.recordsPerSecond(leaves, elapsed),
				entities[0],
				0,
				null,
				reconciliationMs,
				0,
				0,
				Math.max(heapBefore, BenchmarkMetrics.heapUsedBytes()),
				BenchmarkMetrics.heapUsedBytes(),
				"MEASURED",
				started,
				Instant.now(),
				extras);
	}

	private EntityRelationshipBatchProcessor processor() {
		CsvSourceConnector csv = csv();
		EntityExtractor extractor = new EntityExtractor(new MappingEngine(new TransformationEngine()));
		EntityTargetComposer composer = new EntityTargetComposer(new MappingEngine(new TransformationEngine()), new ValidationEngine());
		MigrationTargetConnector target = new MigrationTargetConnector() {
			@Override
			public String targetType() {
				return "STAGING";
			}

			@Override
			public boolean supportsLoadMode(LoadMode loadMode) {
				return loadMode == LoadMode.BULK;
			}

			@Override
			public TargetLoadResult load(
					com.etp.migration.connector.target.MigrationTargetContext context,
					List<LoadableTargetRecord> records) {
				return new TargetLoadResult(records.size(), records.size(), 0, List.of(), List.of());
			}
		};
		TargetIdentityLookup lookup = (entityName, sourceIdentities) -> {
			Map<String, String> resolved = new LinkedHashMap<>();
			for (String sourceIdentity : sourceIdentities) {
				resolved.put(sourceIdentity, "T-" + sourceIdentity);
			}
			return resolved;
		};
		ObjectProvider<TargetIdentityLookup> lookups = new ObjectProvider<>() {
			@Override
			public TargetIdentityLookup getObject() {
				return lookup;
			}

			@Override
			public TargetIdentityLookup getIfAvailable() {
				return lookup;
			}
		};
		return new EntityRelationshipBatchProcessor(
				new EntityCsvAccess(csv, extractor, new MultiFileEntitySource(csv, extractor)),
				composer,
				new MigrationTargetConnectorRegistry(List.of(target)),
				lookups);
	}

	private EntityRelationshipPlanSource planSource() {
		CsvSourceConnector csv = csv();
		EntityExtractor extractor = new EntityExtractor(new MappingEngine(new TransformationEngine()));
		return new EntityRelationshipPlanSource(
				new EntityCsvAccess(csv, extractor, new MultiFileEntitySource(csv, extractor)),
				new RelationshipResolver(),
				new LogicalMigrationScopeBuilder(),
				new ExecutionPlanner());
	}

	private CsvSourceConnector csv() {
		FileSystemArtifactStorage storage = new FileSystemArtifactStorage(tempDir);
		storage.initializeRoot();
		return new CsvSourceConnector(new DefaultArtifactStorage(storage));
	}

	private static EntityRelationshipMapping mapping() {
		return new EntityRelationshipMapping(
				"EntityA",
				List.of(
						new EntityDefinition("EntityA", "entity_a", "entity_a_id", "id"),
						new EntityDefinition("EntityB", "entity_b", "entity_b_id", "id"),
						new EntityDefinition("EntityC", "entity_c", "entity_c_id", "id")),
				List.of(
						new RelationshipDefinition(
								"EntityA",
								"EntityB",
								RelationshipCardinality.ONE_TO_MANY,
								List.of(new RelationshipKey("entity_a_id", "entity_b_parent"))),
						new RelationshipDefinition(
								"EntityB",
								"EntityC",
								RelationshipCardinality.ONE_TO_MANY,
								List.of(new RelationshipKey("entity_b_id", "entity_c_parent")))));
	}

	private static List<MigrationMappingRule> rules() {
		return List.of(
				rule(1, "EntityA", "entity_a_id", "id"),
				rule(2, "EntityB", "entity_b_id", "id"),
				rule(3, "EntityB", "entity_b_parent", "parentId"),
				rule(4, "EntityC", "entity_c_id", "id"),
				rule(5, "EntityC", "entity_c_parent", "parentId"));
	}

	private static MigrationMappingRule rule(int sequence, String entity, String source, String target) {
		MigrationMappingRule rule = new MigrationMappingRule();
		rule.setSequenceNo(sequence);
		rule.setEntityName(entity);
		rule.setSourceField(source);
		rule.setTargetField(target);
		rule.setTransformationType(TransformationType.DIRECT);
		rule.setEnabled(true);
		rule.setConfiguration(Map.of());
		return rule;
	}

	private static Map<String, Object> singleLayout(String file) {
		Map<String, Object> configuration = new LinkedHashMap<>();
		configuration.put("provider", "FILE_SYSTEM");
		configuration.put("fileFormat", "CSV");
		configuration.put("hasHeader", true);
		configuration.put("objectKey", file);
		return configuration;
	}

	private static Map<String, Object> multiLayout(String folder) {
		Map<String, Object> files = new LinkedHashMap<>();
		files.put("EntityA", Map.of("file", folder + "/entity_a.csv"));
		files.put("EntityB", Map.of("file", folder + "/entity_b.csv"));
		files.put("EntityC", Map.of("file", folder + "/entity_c.csv"));
		Map<String, Object> configuration = new LinkedHashMap<>();
		configuration.put("layoutType", "MULTI_FILE");
		configuration.put("provider", "FILE_SYSTEM");
		configuration.put("fileFormat", "CSV");
		configuration.put("hasHeader", true);
		configuration.put("files", files);
		return configuration;
	}
}
