package com.etp.migration.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.etp.migration.connector.source.csv.CsvSourceConnector;
import com.etp.migration.connector.source.csv.MultiFileEntitySource;
import com.etp.migration.connector.storage.DefaultArtifactStorage;
import com.etp.migration.connector.storage.FileSystemArtifactStorage;
import com.etp.migration.connector.target.LoadableTargetRecord;
import com.etp.migration.connector.target.MigrationTargetConnector;
import com.etp.migration.connector.target.MigrationTargetConnectorRegistry;
import com.etp.migration.connector.target.TargetLoadResult;
import com.etp.migration.entity.LoadMode;
import com.etp.migration.entity.MigrationMappingRule;
import com.etp.migration.entity.TransformationType;
import com.etp.migration.extraction.EntityExtractor;
import com.etp.migration.extraction.EntityRecord;
import com.etp.migration.identity.EntityIdentity;
import com.etp.migration.identity.IdentityResolutionService;
import com.etp.migration.identity.TargetIdentityLookup;
import com.etp.migration.mapping.EntityDefinition;
import com.etp.migration.mapping.EntityRelationshipConfiguration;
import com.etp.migration.mapping.EntityRelationshipMapping;
import com.etp.migration.mapping.RelationshipCardinality;
import com.etp.migration.mapping.RelationshipDefinition;
import com.etp.migration.mapping.RelationshipKey;
import com.etp.migration.performance.EntityDatasetGenerator;
import com.etp.migration.planning.ExecutionBatchPlan;
import com.etp.migration.planning.ExecutionPlanner;
import com.etp.migration.planning.ExecutionWorkItem;
import com.etp.migration.processing.EntityIdempotencyKey;
import com.etp.migration.processing.FlatIdempotencyKey;
import com.etp.migration.processing.PipelineTiming;
import com.etp.migration.reconciliation.EntityOutcome;
import com.etp.migration.reconciliation.EntityReconciliationAccumulator;
import com.etp.migration.relationship.RelationshipResolution;
import com.etp.migration.relationship.RelationshipResolver;
import com.etp.migration.relationship.ResolvedRelationship;
import com.etp.migration.scope.LogicalMigrationScope;
import com.etp.migration.scope.LogicalMigrationScopeBuilder;
import com.etp.migration.transformation.MappingEngine;
import com.etp.migration.transformation.TransformationEngine;
import com.etp.migration.validation.ValidationEngine;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

class Phase10ScalabilityTest {

	@TempDir
	Path tempDir;

	private final LogicalMigrationScopeBuilder scopeBuilder = new LogicalMigrationScopeBuilder();
	private final RelationshipResolver relationshipResolver = new RelationshipResolver();
	private final ExecutionPlanner planner = new ExecutionPlanner();

	@AfterEach
	void releaseTiming() {
		PipelineTiming.bind(null);
	}

	@Test
	void scopeIndexGrowsLinearlyAndIsReleasedWhenTheStreamCloses() {
		long bytes1k = scopeBytes(1_000);
		long bytes10k = scopeBytes(10_000);
		long bytes100k = scopeBytes(100_000);
		assertThat(bytes1k).isPositive();
		assertThat((double) bytes10k / bytes1k).isBetween(8.0, 13.0);
		assertThat((double) bytes100k / bytes10k).isBetween(8.0, 13.0);
		assertThat(bytes100k / 100_000).isLessThan(2_000);

		PipelineTiming later = new PipelineTiming();
		PipelineTiming.bind(later);
		try (Stream<LogicalMigrationScope> scopes = scopeBuilder.build(chain(), entities(2), relationships(2))) {
			assertThat(scopes.count()).isEqualTo(1);
		}
		assertThat(later.counter("scopeIdentityCount")).isEqualTo(2);
	}

	@Test
	void relationshipIndexGrowsWithParentsAndIsNotRetainedAsAStaticStructure() {
		long bytes1k = relationshipBytes(1_000);
		long bytes10k = relationshipBytes(10_000);
		long bytes100k = relationshipBytes(100_000);
		assertThat((double) bytes10k / bytes1k).isBetween(8.0, 13.0);
		assertThat((double) bytes100k / bytes10k).isBetween(8.0, 13.0);
		assertThat(bytes100k / 100_000).isLessThan(2_000);
	}

	@Test
	void oneScopeOfEachMeasuredSizeStaysOneRoot() {
		assertThat(maxMembers(1_000)).isEqualTo(1_000);
		assertThat(maxMembers(10_000)).isEqualTo(10_000);
		assertThat(maxMembers(100_000)).isEqualTo(100_000);
	}

	@Test
	void executionBatchesStayWithinTheConfiguredSize() {
		int entities = 250;
		int batchSize = 100;
		List<ExecutionBatchPlan> batches = new ArrayList<>();
		try (Stream<LogicalMigrationScope> scopes = scopeBuilder.build(chain(), entities(entities), relationships(entities));
				Stream<ExecutionBatchPlan> plans = planner.plan(chain(), scopes, batchSize)) {
			plans.forEach(batches::add);
		}
		assertThat(batches).isNotEmpty();
		assertThat(batches).allSatisfy(batch -> {
			assertThat(batch.workItems()).isNotEmpty();
			assertThat(batch.workItems()).hasSizeLessThanOrEqualTo(batchSize);
		});
		assertThat(batches.stream().mapToInt(batch -> batch.workItems().size()).sum()).isEqualTo(entities);
	}

	@Test
	void identityLookupStaysChunked() {
		int count = 250;
		int chunkSize = 100;
		List<Integer> chunkSizes = new ArrayList<>();
		TargetIdentityLookup lookup = (entityName, sourceIdentities) -> {
			chunkSizes.add(sourceIdentities.size());
			Map<String, String> resolved = new LinkedHashMap<>();
			for (String sourceIdentity : sourceIdentities) {
				resolved.put(sourceIdentity, "T-" + sourceIdentity);
			}
			return resolved;
		};
		List<ExecutionWorkItem> items = IntStream.rangeClosed(1, count)
				.mapToObj(index -> new ExecutionWorkItem("EntityA", "A" + index, index, "EntityA", "A1", List.of()))
				.toList();
		PipelineTiming timing = new PipelineTiming();
		PipelineTiming.bind(timing);
		new IdentityResolutionService().resolve(
				singleEntity(),
				new ExecutionBatchPlan(1, items),
				lookup,
				chunkSize);
		assertThat(chunkSizes).allMatch(size -> size <= chunkSize);
		assertThat(chunkSizes.stream().mapToInt(Integer::intValue).sum()).isEqualTo(count);
		assertThat(timing.counter("identityLookupCalls")).isEqualTo(3);
	}

	@Test
	void entityIdempotencyKeyIsDeterministicAndExcludesBatch() {
		UUID runId = UUID.fromString("11111111-1111-1111-1111-111111111111");
		String first = EntityIdempotencyKey.of(runId, "EntityA", "A1");
		String again = EntityIdempotencyKey.of(runId, " EntityA ", " A1 ");
		String otherEntity = EntityIdempotencyKey.of(runId, "EntityB", "A1");
		String otherSource = EntityIdempotencyKey.of(runId, "EntityA", "A2");
		assertThat(first).isEqualTo(again);
		assertThat(first).startsWith(EntityIdempotencyKey.PREFIX).hasSize(EntityIdempotencyKey.PREFIX.length() + 64);
		assertThat(otherEntity).isNotEqualTo(first);
		assertThat(otherSource).isNotEqualTo(first);
		assertThat(EntityIdempotencyKey.isEntityKey(first)).isTrue();
		assertThat(EntityIdempotencyKey.isEntityKey(FlatIdempotencyKey.of(runId, 7L, 105L))).isFalse();
	}

	@Test
	void flatIdempotencyKeyKeepsRunBatchAndSourceRecordNumber() {
		UUID runId = UUID.fromString("22222222-2222-2222-2222-222222222222");
		assertThat(FlatIdempotencyKey.of(runId, 11L, 10001L)).isEqualTo(runId + ":11:10001");
	}

	@Test
	void repeatedEntityIdentityLoadsOnce() {
		UUID runId = UUID.randomUUID();
		EntityRecord record = new EntityRecord("EntityA", 105L, "A1", Map.of("label", "one"));
		ExecutionWorkItem item = new ExecutionWorkItem("EntityA", "A1", 105L, "EntityA", "A1", List.of());
		List<LoadableTargetRecord> loaded = new ArrayList<>();
		MigrationTargetConnector target = recordingTarget(loaded);
		EntityRelationshipBatchExecutor executor = new EntityRelationshipBatchExecutor(
				new EntityTargetComposer(new MappingEngine(new TransformationEngine()), new ValidationEngine()));
		Map<EntityIdentity, EntityRecord> payloads = new LinkedHashMap<>();
		payloads.put(new EntityIdentity("EntityA", "A1"), record);
		EntityBatchOutcome outcome = executor.execute(
				singleEntity(),
				List.of(rule(1, "EntityA", "label", "label")),
				new ExecutionBatchPlan(1, List.of(item, item)),
				payloads,
				(entityName, sourceIdentities) -> Map.of("A1", "T-A1"),
				100,
				target,
				EntityRelationshipBatchExecutor.context(runId, 1L, 1, "STAGING", "entity_a", LoadMode.BULK, Map.of()));
		assertThat(outcome.counters().successCount()).isEqualTo(1);
		assertThat(outcome.counters().duplicateCount()).isEqualTo(1);
		assertThat(loaded).hasSize(1);
		assertThat(loaded.get(0).idempotencyKey()).isEqualTo(EntityIdempotencyKey.of(runId, "EntityA", "A1"));
		payloads.clear();
		assertThat(payloads).isEmpty();
		assertThat(loaded).hasSize(1);
	}

	@Test
	void sourceMatchingKeepsOnlyTheRequestedBatch() throws Exception {
		EntityDatasetGenerator.writeSingle(tempDir.resolve("entities.csv"), 30);
		EntityCsvAccess access = csvAccess();
		EntityRelationshipMapping mapping = threeEntityMapping();
		EntityCsvRecords records = access.open(singleFile("entities.csv"), mapping, rules());
		Map<EntityIdentity, EntityRecord> matched = records.matching(java.util.Set.of(
				new EntityIdentity("EntityA", "A1"),
				new EntityIdentity("EntityC", "C1")));
		assertThat(matched).hasSize(2);
		matched.clear();
		assertThat(records.matching(java.util.Set.of(new EntityIdentity("EntityB", "B1")))).hasSize(1);
	}

	@Test
	void multiFilePrepareOpensEachEntityABoundedNumberOfTimes() throws Exception {
		EntityDatasetGenerator.writeMulti(tempDir, 20);
		PipelineTiming timing = new PipelineTiming();
		PipelineTiming.bind(timing);
		List<ExecutionBatchPlan> batches = new ArrayList<>();
		planSource().consume(
				multiFileLayout(),
				threeEntityMapping(),
				rules(),
				4,
				batches::add,
				failure -> {
					throw new AssertionError(failure.message());
				});
		int entityTypes = 3;
		assertThat(timing.counter("sourceFileOpens")).isPositive().isLessThanOrEqualTo(entityTypes * 3L);
		assertThat(timing.counter("sourceFileOpens")).isLessThan(entityTypes * entityTypes * entityTypes);
		assertThat(batches).allSatisfy(batch -> assertThat(batch.workItems()).hasSizeLessThanOrEqualTo(4));
		assertThat(timing.counter("relationshipIndexedParents")).isEqualTo(2);
		assertThat(timing.counter("relationshipResultCount")).isEqualTo(21);
	}

	@Test
	void reconciliationKeepsABoundedExampleList() {
		EntityReconciliationAccumulator accumulator = new EntityReconciliationAccumulator();
		for (int index = 0; index < 150; index++) {
			accumulator.noteFailure("EntityA", "A" + index, EntityOutcome.FAILURE, "failed");
		}
		Map<String, Object> details = accumulator.finish().details();
		assertThat((List<?>) details.get("examples")).hasSize(EntityReconciliationAccumulator.DEFAULT_EXAMPLE_LIMIT);
		assertThat(details.get("examplesOmitted")).isEqualTo(50L);
	}

	@Test
	void processedBatchReleasesThePayloadMap() throws Exception {
		EntityDatasetGenerator.writeSingle(tempDir.resolve("entities.csv"), 30);
		List<LoadableTargetRecord> loaded = new ArrayList<>();
		EntityRelationshipBatchProcessor processor = new EntityRelationshipBatchProcessor(
				csvAccess(),
				new EntityTargetComposer(new MappingEngine(new TransformationEngine()), new ValidationEngine()),
				new MigrationTargetConnectorRegistry(List.of(recordingTarget(loaded))),
				lookupProvider());
		ExecutionWorkItem item = new ExecutionWorkItem("EntityA", "A1", 1L, "EntityA", "A1", List.of());
		StoredEntityBatch batch = new StoredEntityBatch(
				9L,
				1,
				1,
				UUID.randomUUID(),
				singleFile("entities.csv"),
				EntityRelationshipConfiguration.toConfiguration(threeEntityMapping()),
				rules(),
				EntityBatchLocator.write(new ExecutionBatchPlan(1, List.of(item))),
				"STAGING",
				"entity_a",
				LoadMode.BULK,
				Map.of("target", Map.of("schema", "migration_staging", "stagingTable", "entity_identity_load")));
		EntityBatchOutcome outcome = processor.process(batch);
		assertThat(outcome.counters().successCount()).isEqualTo(1);
		assertThat(loaded).hasSize(1);
		assertThat(processor.process(batch).counters().successCount()).isEqualTo(1);
	}

	private long scopeBytes(int entities) {
		PipelineTiming timing = new PipelineTiming();
		PipelineTiming.bind(timing);
		try (Stream<LogicalMigrationScope> scopes = scopeBuilder.build(chain(), entities(entities), relationships(entities))) {
			assertThat(scopes.mapToInt(scope -> scope.entities().size()).sum()).isEqualTo(entities);
		}
		assertThat(timing.counter("scopeIdentityCount")).isEqualTo(entities);
		assertThat(timing.counter("scopeRelationshipCount")).isEqualTo(entities - 1L);
		assertThat(timing.counter("maxScopeMembers")).isEqualTo(entities);
		return timing.counter("scopeIndexBytes");
	}

	private long maxMembers(int entities) {
		PipelineTiming timing = new PipelineTiming();
		PipelineTiming.bind(timing);
		try (Stream<LogicalMigrationScope> scopes = scopeBuilder.build(chain(), entities(entities), relationships(entities))) {
			scopes.forEach(scope -> { });
		}
		return timing.counter("maxScopeMembers");
	}

	private long relationshipBytes(int parents) {
		PipelineTiming timing = new PipelineTiming();
		PipelineTiming.bind(timing);
		Map<String, List<EntityRecord>> byEntity = new LinkedHashMap<>();
		List<EntityRecord> parentRows = new ArrayList<>(parents);
		List<EntityRecord> childRows = new ArrayList<>(parents);
		for (int index = 1; index <= parents; index++) {
			String parentId = "A" + index;
			parentRows.add(new EntityRecord("EntityA", index, parentId, Map.of("entity_a_id", parentId)));
			childRows.add(new EntityRecord("EntityB", index, "B" + index, Map.of("entity_b_parent", parentId, "entity_b_id", "B" + index)));
		}
		byEntity.put("EntityA", parentRows);
		byEntity.put("EntityB", childRows);
		RelationshipResolution resolution = relationshipResolver.resolve(chain(), entityName -> byEntity.getOrDefault(entityName, List.of()).stream());
		assertThat(resolution.indexedParentCount()).isEqualTo(parents);
		assertThat(resolution.relationships()).hasSize(parents);
		assertThat(timing.counter("relationshipIndexedParents")).isEqualTo(parents);
		return timing.counter("relationshipIndexBytes");
	}

	private static Stream<EntityRecord> entities(int count) {
		return IntStream.rangeClosed(1, count).mapToObj(index -> {
			if (index == 1) {
				return new EntityRecord("EntityA", 1, "A1", Map.of());
			}
			return new EntityRecord("EntityB", index, "B" + index, Map.of());
		});
	}

	private static Stream<ResolvedRelationship> relationships(int count) {
		return IntStream.rangeClosed(2, count).mapToObj(index -> new ResolvedRelationship(
				"EntityA",
				"A1",
				1,
				"EntityB",
				"B" + index,
				index,
				RelationshipCardinality.ONE_TO_MANY,
				List.of()));
	}

	private static EntityRelationshipMapping chain() {
		return new EntityRelationshipMapping(
				"EntityA",
				List.of(
						new EntityDefinition("EntityA", "entity_a", "entity_a_id", "id"),
						new EntityDefinition("EntityB", "entity_b", "entity_b_id", "id")),
				List.of(new RelationshipDefinition(
						"EntityA",
						"EntityB",
						RelationshipCardinality.ONE_TO_MANY,
						List.of(new RelationshipKey("entity_a_id", "entity_b_parent")))));
	}

	private static EntityRelationshipMapping singleEntity() {
		return new EntityRelationshipMapping(
				"EntityA",
				List.of(new EntityDefinition("EntityA", "entity_a", "label", "id")),
				List.of());
	}

	private static EntityRelationshipMapping threeEntityMapping() {
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
				rule(2, "EntityA", "entity_a_label", "label"),
				rule(3, "EntityB", "entity_b_id", "id"),
				rule(4, "EntityB", "entity_b_parent", "parentId"),
				rule(5, "EntityC", "entity_c_id", "id"),
				rule(6, "EntityC", "entity_c_parent", "parentId"));
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

	private EntityCsvAccess csvAccess() {
		FileSystemArtifactStorage storage = new FileSystemArtifactStorage(tempDir);
		storage.initializeRoot();
		CsvSourceConnector csv = new CsvSourceConnector(new DefaultArtifactStorage(storage));
		EntityExtractor extractor = new EntityExtractor(new MappingEngine(new TransformationEngine()));
		return new EntityCsvAccess(csv, extractor, new MultiFileEntitySource(csv, extractor));
	}

	private EntityRelationshipPlanSource planSource() {
		return new EntityRelationshipPlanSource(csvAccess(), relationshipResolver, scopeBuilder, planner);
	}

	private static Map<String, Object> singleFile(String file) {
		Map<String, Object> configuration = new LinkedHashMap<>();
		configuration.put("provider", "FILE_SYSTEM");
		configuration.put("fileFormat", "CSV");
		configuration.put("hasHeader", true);
		configuration.put("objectKey", file);
		return configuration;
	}

	private static Map<String, Object> multiFileLayout() {
		Map<String, Object> files = new LinkedHashMap<>();
		files.put("EntityA", Map.of("file", "entity_a.csv"));
		files.put("EntityB", Map.of("file", "entity_b.csv"));
		files.put("EntityC", Map.of("file", "entity_c.csv"));
		Map<String, Object> configuration = new LinkedHashMap<>();
		configuration.put("layoutType", "MULTI_FILE");
		configuration.put("provider", "FILE_SYSTEM");
		configuration.put("fileFormat", "CSV");
		configuration.put("hasHeader", true);
		configuration.put("files", files);
		return configuration;
	}

	private static MigrationTargetConnector recordingTarget(List<LoadableTargetRecord> loaded) {
		return new MigrationTargetConnector() {
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
				loaded.clear();
				loaded.addAll(records);
				return new TargetLoadResult(records.size(), records.size(), 0, List.of(), List.of());
			}
		};
	}

	private static ObjectProvider<TargetIdentityLookup> lookupProvider() {
		TargetIdentityLookup lookup = (entityName, sourceIdentities) -> {
			Map<String, String> resolved = new LinkedHashMap<>();
			for (String sourceIdentity : sourceIdentities) {
				resolved.put(sourceIdentity, "T-" + sourceIdentity);
			}
			return resolved;
		};
		return new ObjectProvider<>() {
			@Override
			public TargetIdentityLookup getObject() {
				return lookup;
			}

			@Override
			public TargetIdentityLookup getIfAvailable() {
				return lookup;
			}
		};
	}
}
