package com.etp.migration.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.etp.migration.connector.source.csv.CsvSourceConnector;
import com.etp.migration.connector.source.csv.MultiFileEntitySource;
import com.etp.migration.connector.storage.DefaultArtifactStorage;
import com.etp.migration.connector.storage.FileSystemArtifactStorage;
import com.etp.migration.connector.target.StagedEntityView;
import com.etp.migration.connector.target.StagedRelationshipView;
import com.etp.migration.connector.target.TargetVerification;
import com.etp.migration.entity.MigrationMappingRule;
import com.etp.migration.entity.MigrationRun;
import com.etp.migration.entity.MigrationRunStatus;
import com.etp.migration.entity.ReconciliationStatus;
import com.etp.migration.entity.ReconciliationType;
import com.etp.migration.entity.TransformationType;
import com.etp.migration.execution.EntityBatchLocator;
import com.etp.migration.execution.EntityCsvAccess;
import com.etp.migration.execution.EntityItemFailure;
import com.etp.migration.execution.EntityRelationshipPlanSource;
import com.etp.migration.extraction.EntityExtractor;
import com.etp.migration.mapping.EntityDefinition;
import com.etp.migration.mapping.EntityRelationshipMapping;
import com.etp.migration.mapping.RelationshipCardinality;
import com.etp.migration.mapping.RelationshipDefinition;
import com.etp.migration.mapping.RelationshipKey;
import com.etp.migration.planning.ExecutionBatchPlan;
import com.etp.migration.planning.ExecutionPlanner;
import com.etp.migration.planning.ExecutionWorkItem;
import com.etp.migration.planning.WorkItemParent;
import com.etp.migration.processing.MigrationErrorCodes;
import com.etp.migration.relationship.RelationshipResolver;
import com.etp.migration.scope.LogicalMigrationScopeBuilder;
import com.etp.migration.transformation.MappingEngine;
import com.etp.migration.transformation.TransformationEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EntityReconciliationTest {

	@Test
	void entityCountExactMatchPasses() {
		EntityReconciliationResult result = counts(Map.of("Location", 10, "Address", 20, "Site", 30));

		assertThat(result.status()).isEqualTo(ReconciliationStatus.PASSED);
		assertThat(entity(result, "Location").get("difference")).isEqualTo(0L);
		assertThat(entity(result, "Address").get("sourceCount")).isEqualTo(20L);
		assertThat(entity(result, "Site").get("processedCount")).isEqualTo(30L);
		assertThat(overall(result).get("sourceEntityCount")).isEqualTo(60L);
		assertThat(overall(result).get("failedEntityCount")).isEqualTo(0L);
		assertThat(overall(result).get("extractedEntityCount")).isEqualTo(EntityReconciliationAccumulator.UNAVAILABLE);
	}

	@Test
	void entityCountMismatchReportsTheDifference() {
		EntityReconciliationAccumulator accumulator = new EntityReconciliationAccumulator();
		for (int index = 0; index < 18; index++) {
			accumulator.observe(EntityObservation.success("Address", "A" + index, "T" + index, List.of()));
		}
		accumulator.observe(EntityObservation.unprocessed("Address", "A18"));
		accumulator.observe(EntityObservation.unprocessed("Address", "A19"));

		EntityReconciliationResult result = accumulator.finish();

		assertThat(result.status()).isEqualTo(ReconciliationStatus.FAILED);
		assertThat(entity(result, "Address").get("sourceCount")).isEqualTo(20L);
		assertThat(entity(result, "Address").get("processedCount")).isEqualTo(18L);
		assertThat(entity(result, "Address").get("difference")).isEqualTo(2L);
	}

	@Test
	void partialEntityFailureCountsSuccessAndFailure() {
		EntityReconciliationAccumulator accumulator = new EntityReconciliationAccumulator();
		for (int index = 0; index < 98; index++) {
			accumulator.observe(EntityObservation.success("Item", "S" + index, "T" + index, List.of()));
		}
		accumulator.observe(EntityObservation.outcome("Item", "S98", EntityOutcome.FAILURE, null, List.of()));
		accumulator.observe(EntityObservation.outcome("Item", "S99", EntityOutcome.FAILURE, null, List.of()));

		EntityReconciliationResult result = accumulator.finish();

		assertThat(result.status()).isEqualTo(ReconciliationStatus.FAILED);
		assertThat(entity(result, "Item").get("sourceCount")).isEqualTo(100L);
		assertThat(entity(result, "Item").get("successCount")).isEqualTo(98L);
		assertThat(entity(result, "Item").get("failureCount")).isEqualTo(2L);
	}

	@Test
	void unresolvedIdentityIsCountedAndNotUsedAsATargetId() {
		EntityReconciliationAccumulator accumulator = new EntityReconciliationAccumulator();
		for (int index = 0; index < 98; index++) {
			accumulator.observe(EntityObservation.success("Item", "S" + index, "T" + index, List.of()));
		}
		accumulator.observe(EntityObservation.outcome("Item", "S98", EntityOutcome.IDENTITY_UNRESOLVED, null, List.of()));
		accumulator.observe(EntityObservation.outcome("Item", "S99", EntityOutcome.IDENTITY_UNRESOLVED, null, List.of()));

		EntityReconciliationResult result = accumulator.finish();

		assertThat(result.status()).isEqualTo(ReconciliationStatus.FAILED);
		assertThat(entity(result, "Item").get("identityResolvedCount")).isEqualTo(98L);
		assertThat(entity(result, "Item").get("identityUnresolvedCount")).isEqualTo(2L);
		assertThat(examples(result)).anySatisfy(example -> {
			assertThat(example.get("sourceIdentity")).isEqualTo("S98");
			assertThat(example.get("reason")).isEqualTo("TARGET_IDENTITY_NOT_FOUND");
			assertThat(example.get("resolvedTargetIdentity")).isEqualTo(EntityReconciliationAccumulator.UNRESOLVED_TARGET);
			assertThat(example.get("resolvedTargetIdentity")).isNotEqualTo("S98");
		});
	}

	@Test
	void resolvedParentAndChildTargetsPass() {
		EntityReconciliationResult result = parentAndChild(
				RelationshipFact.resolved("Parent", "P1", "TP", "TC"));

		assertThat(result.status()).isEqualTo(ReconciliationStatus.PASSED);
		assertThat(relationship(result, "Parent", "Child").get("status")).isEqualTo("PASSED");
		assertThat(relationship(result, "Parent", "Child").get("resolved")).isEqualTo(1L);
		assertThat(relationship(result, "Parent", "Child").get("unresolved")).isEqualTo(0L);
	}

	@Test
	void stagedEntitiesPassWhenTargetIdentitiesAreNull() {
		EntityReconciliationAccumulator accumulator = new EntityReconciliationAccumulator();
		accumulator.observe(EntityObservation.success("Parent", "P1", null, List.of()));
		accumulator.observe(EntityObservation.success(
				"Child",
				"C1",
				null,
				List.of(RelationshipFact.observed("Parent", "P1", null, null))));

		EntityReconciliationResult result = accumulator.finish();

		assertThat(result.status()).isEqualTo(ReconciliationStatus.PASSED);
		assertThat(entity(result, "Parent").get("successCount")).isEqualTo(1L);
		assertThat(entity(result, "Parent").get("identityUnresolvedCount")).isEqualTo(0L);
		assertThat(entity(result, "Child").get("identityUnresolvedCount")).isEqualTo(0L);
		assertThat(entity(result, "Parent").get("identityResolvedCount")).isEqualTo(0L);
		assertThat(relationship(result, "Parent", "Child").get("status")).isEqualTo("PASSED");
		assertThat(relationship(result, "Parent", "Child").get("resolved")).isEqualTo(1L);
		assertThat(relationship(result, "Parent", "Child").get("unresolved")).isEqualTo(0L);
		assertThat(examples(result)).isEmpty();
	}

	@Test
	void recordedParentTargetIssueStillFails() {
		EntityReconciliationResult result = parentAndChild(
				RelationshipFact.issue("Parent", "P1", "TC", RelationshipCondition.PARENT_TARGET_UNRESOLVED, null));

		assertThat(result.status()).isEqualTo(ReconciliationStatus.FAILED);
		assertThat(relationship(result, "Parent", "Child").get("reason"))
				.isEqualTo(RelationshipCondition.PARENT_TARGET_UNRESOLVED.name());
		assertThat(relationship(result, "Parent", "Child").get("unresolved")).isEqualTo(1L);
	}

	@Test
	void explicitUnresolvedIdentityStillFailsWhenTheParentSourceIsPresent() {
		EntityReconciliationAccumulator accumulator = new EntityReconciliationAccumulator();
		accumulator.observe(EntityObservation.success("Parent", "P1", "TP", List.of()));
		accumulator.observe(EntityObservation.outcome(
				"Child",
				"C1",
				EntityOutcome.IDENTITY_UNRESOLVED,
				null,
				List.of(RelationshipFact.observed("Parent", "P1", null, null))));

		EntityReconciliationResult result = accumulator.finish();

		assertThat(result.status()).isEqualTo(ReconciliationStatus.FAILED);
		assertThat(entity(result, "Child").get("identityUnresolvedCount")).isEqualTo(1L);
		assertThat(relationship(result, "Parent", "Child").get("status")).isEqualTo("PASSED");
		assertThat(examples(result)).anySatisfy(example ->
				assertThat(example.get("resolvedTargetIdentity")).isEqualTo(EntityReconciliationAccumulator.UNRESOLVED_TARGET));
	}

	@Test
	void missingRelationshipReferenceFails() {
		EntityReconciliationResult result = parentAndChild(
				RelationshipFact.issue("Parent", "P1", "TC", RelationshipCondition.REFERENCE_MISSING, null));

		assertThat(result.status()).isEqualTo(ReconciliationStatus.FAILED);
		assertThat(relationship(result, "Parent", "Child").get("reason"))
				.isEqualTo(RelationshipCondition.REFERENCE_MISSING.name());
	}

	@Test
	void relationshipTargetMatchingTheSourceIdentityIsInconsistent() {
		EntityReconciliationResult result = parentAndChild(
				RelationshipFact.observed("Parent", "P1", "P1", "TC"));

		assertThat(result.status()).isEqualTo(ReconciliationStatus.FAILED);
		assertThat(relationship(result, "Parent", "Child").get("reason"))
				.isEqualTo(RelationshipCondition.INCONSISTENT.name());
		assertThat(examples(result)).allSatisfy(example ->
				assertThat(example.get("resolvedTargetIdentity")).isNotEqualTo("P1"));
	}

	@Test
	void oneToOneCardinalityViolationFails() {
		EntityReconciliationAccumulator accumulator = new EntityReconciliationAccumulator();
		accumulator.noteCardinality("Parent", "Child", RelationshipCardinality.ONE_TO_ONE.name());
		accumulator.observe(EntityObservation.success("Parent", "P1", "TP", List.of()));
		accumulator.observe(EntityObservation.outcome(
				"Child",
				"C1",
				EntityOutcome.FAILURE,
				null,
				List.of(RelationshipFact.issue(
						"Parent",
						"P1",
						null,
						RelationshipCondition.CARDINALITY_VIOLATION,
						RelationshipCardinality.ONE_TO_ONE.name()))));

		EntityReconciliationResult result = accumulator.finish();

		assertThat(result.status()).isEqualTo(ReconciliationStatus.FAILED);
		assertThat(relationship(result, "Parent", "Child").get("cardinality"))
				.isEqualTo(RelationshipCardinality.ONE_TO_ONE.name());
		assertThat(relationship(result, "Parent", "Child").get("reason"))
				.isEqualTo(RelationshipCondition.CARDINALITY_VIOLATION.name());
		assertThat(relationship(result, "Parent", "Child").get("unresolved")).isEqualTo(1L);
	}

	@Test
	void oneToManyRelationshipsPass() {
		EntityReconciliationResult result = twoChildren(RelationshipCardinality.ONE_TO_MANY);

		assertThat(result.status()).isEqualTo(ReconciliationStatus.PASSED);
		assertThat(relationship(result, "Parent", "Child").get("expected")).isEqualTo(2L);
		assertThat(relationship(result, "Parent", "Child").get("resolved")).isEqualTo(2L);
		assertThat(relationship(result, "Parent", "Child").get("cardinality"))
				.isEqualTo(RelationshipCardinality.ONE_TO_MANY.name());
	}

	@Test
	void manyToOneRelationshipsPass() {
		EntityReconciliationResult result = twoChildren(RelationshipCardinality.MANY_TO_ONE);

		assertThat(result.status()).isEqualTo(ReconciliationStatus.PASSED);
		assertThat(relationship(result, "Parent", "Child").get("resolved")).isEqualTo(2L);
		assertThat(relationship(result, "Parent", "Child").get("cardinality"))
				.isEqualTo(RelationshipCardinality.MANY_TO_ONE.name());
	}

	@Test
	void multipleParentsStayOnOneChild() {
		EntityReconciliationAccumulator accumulator = new EntityReconciliationAccumulator();
		accumulator.observe(EntityObservation.success("Left", "L1", "TL", List.of()));
		accumulator.observe(EntityObservation.success("Right", "R1", "TR", List.of()));
		accumulator.observe(EntityObservation.success(
				"Child",
				"C1",
				"TC",
				List.of(
						RelationshipFact.resolved("Left", "L1", "TL", "TC"),
						RelationshipFact.resolved("Right", "R1", "TR", "TC"))));

		EntityReconciliationResult result = accumulator.finish();

		assertThat(result.status()).isEqualTo(ReconciliationStatus.PASSED);
		assertThat(entity(result, "Child").get("sourceCount")).isEqualTo(1L);
		assertThat(relationship(result, "Left", "Child").get("resolved")).isEqualTo(1L);
		assertThat(relationship(result, "Right", "Child").get("resolved")).isEqualTo(1L);
	}

	@Test
	void diamondCountsTheSharedChildOnce() {
		EntityReconciliationAccumulator accumulator = new EntityReconciliationAccumulator();
		accumulator.observe(EntityObservation.success("Root", "R1", "TR", List.of()));
		accumulator.observe(EntityObservation.success(
				"Left",
				"L1",
				"TL",
				List.of(RelationshipFact.resolved("Root", "R1", "TR", "TL"))));
		accumulator.observe(EntityObservation.success(
				"Right",
				"G1",
				"TG",
				List.of(RelationshipFact.resolved("Root", "R1", "TR", "TG"))));
		accumulator.observe(EntityObservation.success(
				"Leaf",
				"D1",
				"TD",
				List.of(
						RelationshipFact.resolved("Left", "L1", "TL", "TD"),
						RelationshipFact.resolved("Right", "G1", "TG", "TD"))));

		EntityReconciliationResult result = accumulator.finish();

		assertThat(result.status()).isEqualTo(ReconciliationStatus.PASSED);
		assertThat(entity(result, "Leaf").get("sourceCount")).isEqualTo(1L);
		assertThat(overall(result).get("sourceEntityCount")).isEqualTo(4L);
		assertThat(relationship(result, "Left", "Leaf").get("resolved")).isEqualTo(1L);
		assertThat(relationship(result, "Right", "Leaf").get("resolved")).isEqualTo(1L);
	}

	@Test
	void sameSourceIdentityOnDifferentEntitiesStaysDistinct() {
		EntityReconciliationAccumulator accumulator = new EntityReconciliationAccumulator();
		accumulator.observe(EntityObservation.success("Address", "A001", "TA", List.of()));
		accumulator.observe(EntityObservation.success("Site", "A001", "TS", List.of()));

		EntityReconciliationResult result = accumulator.finish();

		assertThat(result.status()).isEqualTo(ReconciliationStatus.PASSED);
		assertThat(entity(result, "Address").get("sourceCount")).isEqualTo(1L);
		assertThat(entity(result, "Site").get("sourceCount")).isEqualTo(1L);
		assertThat(overall(result).get("sourceEntityCount")).isEqualTo(2L);
	}

	@Test
	void multipleRootScopesAggregateByEntity() {
		EntityReconciliationAccumulator accumulator = new EntityReconciliationAccumulator();
		accumulator.observe(EntityObservation.success("Root", "R1", "T1", List.of()));
		accumulator.observe(EntityObservation.success(
				"Child",
				"C1",
				"TC1",
				List.of(RelationshipFact.resolved("Root", "R1", "T1", "TC1"))));
		accumulator.observe(EntityObservation.success("Root", "R2", "T2", List.of()));
		accumulator.observe(EntityObservation.success(
				"Child",
				"C2",
				"TC2",
				List.of(RelationshipFact.resolved("Root", "R2", "T2", "TC2"))));

		EntityReconciliationResult result = accumulator.finish();

		assertThat(result.status()).isEqualTo(ReconciliationStatus.PASSED);
		assertThat(entity(result, "Root").get("sourceCount")).isEqualTo(2L);
		assertThat(entity(result, "Child").get("sourceCount")).isEqualTo(2L);
		assertThat(relationship(result, "Root", "Child").get("resolved")).isEqualTo(2L);
	}

	@Test
	void multiFileCsvReconciliationCountsConfiguredEntities(@TempDir Path tempDir) throws Exception {
		EntityReconciliationResult result = reconcileCsv(tempDir, multiFile(tempDir));

		assertThat(result.status()).isEqualTo(ReconciliationStatus.PASSED);
		assertThat(overall(result).get("sourceEntityCount")).isEqualTo(3L);
		assertThat(entity(result, "Location").get("sourceCount")).isEqualTo(1L);
		assertThat(entity(result, "Address").get("sourceCount")).isEqualTo(1L);
		assertThat(entity(result, "Site").get("sourceCount")).isEqualTo(1L);
	}

	@Test
	void singleFileEntityRelationshipReconciliationCountsEntitiesNotPhysicalRows(@TempDir Path tempDir) throws Exception {
		EntityReconciliationResult result = reconcileCsv(tempDir, singleFile(tempDir));

		assertThat(result.status()).isEqualTo(ReconciliationStatus.PASSED);
		assertThat(overall(result).get("sourceEntityCount")).isEqualTo(3L);
		assertThat(entity(result, "Location").get("sourceCount")).isEqualTo(1L);
		assertThat(entity(result, "Address").get("sourceCount")).isEqualTo(1L);
		assertThat(entity(result, "Site").get("sourceCount")).isEqualTo(1L);
		assertThat(relationship(result, "Location", "Address").get("resolved")).isEqualTo(1L);
		assertThat(relationship(result, "Address", "Site").get("resolved")).isEqualTo(1L);
	}

	@Test
	void flatReconciliationTypesStayUnchanged() {
		assertThat(ReconciliationType.values()).containsExactly(
				ReconciliationType.COUNT,
				ReconciliationType.CHECKSUM,
				ReconciliationType.TARGET,
				ReconciliationType.FULL);
		ReconciliationEngine engine = new ReconciliationEngine();
		ReconciliationContext context = new ReconciliationContext(
				run(MigrationRunStatus.COMPLETED),
				2,
				2,
				2,
				0,
				0,
				0,
				"abc",
				"SHA-256",
				new TargetVerification(true, 2L, "STAGING", false, "staging only", Map.of()));
		ReconciliationComputation count = engine.count(context);
		ReconciliationComputation checksum = engine.checksum(context);
		ReconciliationComputation target = engine.target(context);
		ReconciliationComputation full = engine.full(context, count, checksum, target);

		assertThat(count.status()).isEqualTo(ReconciliationStatus.PASSED);
		assertThat(count.details()).doesNotContainKey("entityReconciliation");
		assertThat(checksum.status()).isEqualTo(ReconciliationStatus.PASSED_WITH_WARNINGS);
		assertThat(checksum.details().get("comparisonPerformed")).isEqualTo(false);
		assertThat(target.status()).isEqualTo(ReconciliationStatus.PASSED);
		assertThat(full.details()).doesNotContainKey("entityReconciliation");

		EntityReconciliationResult entity = counts(Map.of("Item", 1));
		List<ReconciliationComputation> overlaid = entity.apply(List.of(count, checksum, target, full));
		assertThat(overlaid).extracting(ReconciliationComputation::type).containsExactly(
				ReconciliationType.COUNT,
				ReconciliationType.CHECKSUM,
				ReconciliationType.TARGET,
				ReconciliationType.FULL);
		assertThat(overlaid.get(1)).isSameAs(checksum);
		assertThat(overlaid.get(0).details()).containsKeys("sourceCount", "entityReconciliation");
		assertThat(overlaid.get(2).details()).doesNotContainKey("entityReconciliation");
	}

	@Test
	void emptyMigrationPasses() {
		EntityReconciliationResult result = new EntityReconciliationAccumulator().finish();

		assertThat(result.status()).isEqualTo(ReconciliationStatus.PASSED);
		assertThat(overall(result).get("sourceEntityCount")).isEqualTo(0L);
		assertThat(overall(result).get("processedEntityCount")).isEqualTo(0L);
		assertThat(overall(result).get("successfulEntityCount")).isEqualTo(0L);
		assertThat(overall(result).get("relationshipFailureCount")).isEqualTo(0L);
		assertThat(result.retainedObservationCount()).isZero();
	}

	@Test
	void noSuccessfulEntitiesFails() {
		EntityReconciliationAccumulator accumulator = new EntityReconciliationAccumulator();
		for (int index = 0; index < 5; index++) {
			accumulator.observe(EntityObservation.outcome("Item", "S" + index, EntityOutcome.FAILURE, null, List.of()));
		}

		EntityReconciliationResult result = accumulator.finish();

		assertThat(result.status()).isEqualTo(ReconciliationStatus.FAILED);
		assertThat(entity(result, "Item").get("successCount")).isEqualTo(0L);
		assertThat(entity(result, "Item").get("failureCount")).isEqualTo(5L);
		assertThat(overall(result).get("successfulEntityCount")).isEqualTo(0L);
	}

	@Test
	void discrepancyExamplesStayWithinTheLimit() {
		EntityReconciliationAccumulator accumulator = new EntityReconciliationAccumulator(100);
		for (int index = 0; index < 150; index++) {
			accumulator.observe(EntityObservation.outcome(
					"Item",
					String.format("F%03d", index),
					EntityOutcome.FAILURE,
					null,
					List.of()));
		}

		EntityReconciliationResult result = accumulator.finish();

		assertThat(examples(result)).hasSize(100);
		assertThat(examples(result).get(0).get("sourceIdentity")).isEqualTo("F000");
		assertThat(examples(result).get(99).get("sourceIdentity")).isEqualTo("F099");
		assertThat(examples(result)).noneMatch(example -> "F100".equals(example.get("sourceIdentity")));
		assertThat(result.details().get("examplesOmitted")).isEqualTo(50L);
		assertThat(entity(result, "Item").get("failureCount")).isEqualTo(150L);
		assertThat(result.retainedObservationCount()).isZero();
	}

	@Test
	void batchCountersAggregateWithoutRetainingObservations() {
		EntityReconciliationAccumulator accumulator = new EntityReconciliationAccumulator();
		for (int batch = 0; batch < 3; batch++) {
			accumulator.beginBatch();
			int successCount = batch == 2 ? 998 : 1000;
			for (int index = 0; index < successCount; index++) {
				accumulator.observe(EntityObservation.success("Item", "B" + batch + "-" + index, "T" + batch + "-" + index, List.of()));
			}
			assertThat(accumulator.retainedObservationCount()).isZero();
		}

		EntityReconciliationResult result = accumulator.finish();

		assertThat(entity(result, "Item").get("successCount")).isEqualTo(2998L);
		assertThat(result.details().get("batchesAggregated")).isEqualTo(3L);
		assertThat(examples(result)).isEmpty();
		assertThat(result.retainedObservationCount()).isZero();
		assertThat(result.status()).isEqualTo(ReconciliationStatus.PASSED);
	}

	@Test
	void unavailableTargetCountIsNotFabricated() {
		EntityReconciliationAccumulator accumulator = new EntityReconciliationAccumulator();
		accumulator.notePlanned("Widget", List.of());
		accumulator.markTargetResultsUnavailable();
		accumulator.targetCountUnavailable();

		EntityReconciliationResult result = accumulator.finish();

		assertThat(result.status()).isEqualTo(ReconciliationStatus.PASSED_WITH_WARNINGS);
		assertThat(result.successCount()).isNull();
		assertThat(result.details()).doesNotContainKey("targetCount");
		assertThat(result.details().get("targetCountAvailable")).isEqualTo(false);
		assertThat(entity(result, "Widget").get("successCount")).isEqualTo(EntityReconciliationAccumulator.UNAVAILABLE);
		assertThat(overall(result).get("successfulEntityCount")).isEqualTo(EntityReconciliationAccumulator.UNAVAILABLE);
		assertThat(entity(result, "Widget").get("successCount")).isNotEqualTo(0L);
	}

	@Test
	void theSameInputProducesTheSameResult() {
		EntityReconciliationResult first = sample();
		EntityReconciliationResult second = sample();

		assertThat(second.details()).isEqualTo(first.details());
		assertThat(second.status()).isEqualTo(first.status());
		assertThat(second.sourceCount()).isEqualTo(first.sourceCount());
		assertThat(second.differenceCount()).isEqualTo(first.differenceCount());
	}

	@Test
	void productVariantPriceModelIsDomainNeutral() {
		EntityReconciliationAccumulator accumulator = new EntityReconciliationAccumulator();
		accumulator.observe(EntityObservation.success("Product", "P1", "TP", List.of()));
		accumulator.observe(EntityObservation.success(
				"Variant",
				"V1",
				"TV",
				List.of(RelationshipFact.resolved("Product", "P1", "TP", "TV"))));
		accumulator.observe(EntityObservation.success(
				"Price",
				"R1",
				"TR",
				List.of(RelationshipFact.resolved("Variant", "V1", "TV", "TR"))));

		EntityReconciliationResult result = accumulator.finish();

		assertThat(result.status()).isEqualTo(ReconciliationStatus.PASSED);
		assertThat(entity(result, "Product").get("successCount")).isEqualTo(1L);
		assertThat(entity(result, "Variant").get("successCount")).isEqualTo(1L);
		assertThat(entity(result, "Price").get("successCount")).isEqualTo(1L);
		assertThat(relationship(result, "Product", "Variant").get("resolved")).isEqualTo(1L);
		assertThat(relationship(result, "Variant", "Price").get("resolved")).isEqualTo(1L);
	}

	@Test
	void alphaBetaGammaModelIsDomainNeutral() {
		EntityReconciliationAccumulator accumulator = new EntityReconciliationAccumulator();
		accumulator.observe(EntityObservation.success("Alpha", "A1", "TA", List.of()));
		accumulator.observe(EntityObservation.success(
				"Beta",
				"B1",
				"TB",
				List.of(RelationshipFact.resolved("Alpha", "A1", "TA", "TB"))));
		accumulator.observe(EntityObservation.success(
				"Gamma",
				"G1",
				"TG",
				List.of(RelationshipFact.resolved("Alpha", "A1", "TA", "TG"))));

		EntityReconciliationResult result = accumulator.finish();

		assertThat(result.status()).isEqualTo(ReconciliationStatus.PASSED);
		assertThat(overall(result).get("sourceEntityCount")).isEqualTo(3L);
		assertThat(relationship(result, "Alpha", "Beta").get("resolved")).isEqualTo(1L);
		assertThat(relationship(result, "Alpha", "Gamma").get("resolved")).isEqualTo(1L);
	}

	@Test
	void storedBatchesAndStagedRowsAggregateWithoutResolvingAgain() {
		EntityReconciliationAccumulator accumulator = new EntityReconciliationAccumulator();
		ExecutionWorkItem root = new ExecutionWorkItem("Root", "R1", 1, "Root", "R1", List.of());
		ExecutionWorkItem child = new ExecutionWorkItem(
				"Child",
				"C1",
				2,
				"Root",
				"R1",
				List.of(new WorkItemParent("Root", "R1")));
		accumulator.beginBatch();
		EntityRunFacts.foldLocator(accumulator, EntityBatchLocator.write(new ExecutionBatchPlan(1, List.of(root))));
		accumulator.beginBatch();
		EntityRunFacts.foldLocator(accumulator, EntityBatchLocator.write(new ExecutionBatchPlan(2, List.of(child))));
		accumulator.markTargetResultsKnown();
		EntityRunFacts.foldStaged(accumulator, new StagedEntityView("Root", "R1", "TR", List.of()));
		EntityRunFacts.foldStaged(
				accumulator,
				new StagedEntityView(
						"Child",
						"C1",
						"TC",
						List.of(new StagedRelationshipView("Root", "R1", "TR"))));
		EntityRunFacts.foldError(
				accumulator,
				MigrationErrorCodes.ENTITY_EXTRACTION_FAILED,
				"ENTITY_EXTRACTION_FAILED",
				"missing",
				Map.of("entityName", "Child", "sourceIdentity", "C9"));

		EntityReconciliationResult result = accumulator.finish();

		assertThat(entity(result, "Root").get("processedCount")).isEqualTo(1L);
		assertThat(entity(result, "Child").get("sourceCount")).isEqualTo(1L);
		assertThat(entity(result, "Child").get("plannedCount")).isEqualTo(1L);
		assertThat(entity(result, "Child").get("processedCount")).isEqualTo(1L);
		assertThat(entity(result, "Child").get("difference")).isEqualTo(0L);
		assertThat(examples(result)).noneMatch(example -> "C9".equals(example.get("sourceIdentity")));
		assertThat(relationship(result, "Root", "Child").get("resolved")).isEqualTo(1L);
		assertThat(result.details().get("batchesAggregated")).isEqualTo(2L);
		assertThat(result.retainedObservationCount()).isZero();
		assertThat(result.status()).isEqualTo(ReconciliationStatus.PASSED);
	}

	@Test
	void rowsThatDoNotInstantiateAnEntityAreNotExtractionFailures() {
		EntityReconciliationAccumulator accumulator = new EntityReconciliationAccumulator();
		accumulator.beginBatch();
		List<ExecutionWorkItem> planned = new ArrayList<>();
		List<StagedEntityView> staged = new ArrayList<>();
		for (int index = 1; index <= 101; index++) {
			String addressLocation = "AL" + index;
			String point = "PT" + index;
			String address = "AD" + index;
			String location = "GL" + index;
			String geometry = "GM" + index;
			planned.add(item("AddressLocation", addressLocation, index, List.of()));
			planned.add(item("GeographicPointLocation", point, index,
					List.of(new WorkItemParent("AddressLocation", addressLocation))));
			planned.add(item("GeographicAddress", address, index,
					List.of(
							new WorkItemParent("AddressLocation", addressLocation),
							new WorkItemParent("GeographicLocation", location))));
			planned.add(item("GeographicLocation", location, index,
					List.of(new WorkItemParent("GeographicPointLocation", point))));
			planned.add(item("LocationGeometry", geometry, index,
					List.of(
							new WorkItemParent("GeographicPointLocation", point),
							new WorkItemParent("GeographicLocation", location))));
			staged.add(staged("AddressLocation", addressLocation, List.of()));
			staged.add(staged("GeographicPointLocation", point,
					List.of(link("AddressLocation", addressLocation))));
			staged.add(staged("GeographicAddress", address,
					List.of(link("AddressLocation", addressLocation), link("GeographicLocation", location))));
			staged.add(staged("GeographicLocation", location,
					List.of(link("GeographicPointLocation", point))));
			staged.add(staged("LocationGeometry", geometry,
					List.of(link("GeographicPointLocation", point), link("GeographicLocation", location))));
		}
		for (int index = 1; index <= 10; index++) {
			String subAddress = "SUB" + index;
			planned.add(item("GeographicSubAddress", subAddress, 100 + index,
					List.of(
							new WorkItemParent("GeographicAddress", "AD1"),
							new WorkItemParent("GeographicLocation", "GL1"))));
			staged.add(staged("GeographicSubAddress", subAddress,
					List.of(link("GeographicAddress", "AD1"), link("GeographicLocation", "GL1"))));
		}
		EntityRunFacts.foldLocator(accumulator, EntityBatchLocator.write(new ExecutionBatchPlan(1, planned)));
		accumulator.markTargetResultsKnown();
		for (StagedEntityView view : staged) {
			EntityRunFacts.foldStaged(accumulator, view);
		}
		for (int recordNumber = 1; recordNumber <= 100; recordNumber++) {
			EntityRunFacts.foldError(
					accumulator,
					MigrationErrorCodes.ENTITY_EXTRACTION_FAILED,
					"ENTITY_EXTRACTION_FAILED",
					String.valueOf(recordNumber),
					Map.of("entityName", "GeographicSubAddress", "sourceIdentity", ""));
		}

		EntityReconciliationResult result = accumulator.finish();

		assertThat(entity(result, "AddressLocation")).containsEntry("sourceCount", 101L)
				.containsEntry("plannedCount", 101L).containsEntry("difference", 0L);
		assertThat(entity(result, "GeographicPointLocation")).containsEntry("sourceCount", 101L)
				.containsEntry("plannedCount", 101L).containsEntry("difference", 0L);
		assertThat(entity(result, "GeographicAddress")).containsEntry("sourceCount", 101L)
				.containsEntry("plannedCount", 101L).containsEntry("difference", 0L);
		assertThat(entity(result, "GeographicLocation")).containsEntry("sourceCount", 101L)
				.containsEntry("plannedCount", 101L).containsEntry("difference", 0L);
		assertThat(entity(result, "LocationGeometry")).containsEntry("sourceCount", 101L)
				.containsEntry("plannedCount", 101L).containsEntry("difference", 0L);
		assertThat(entity(result, "GeographicSubAddress")).containsEntry("sourceCount", 10L)
				.containsEntry("plannedCount", 10L).containsEntry("processedCount", 10L)
				.containsEntry("difference", 0L);
		assertThat(overall(result)).containsEntry("sourceEntityCount", 515L)
				.containsEntry("plannedEntityCount", 515L);
		assertThat(result.differenceCount()).isZero();
		assertThat(result.status()).isEqualTo(ReconciliationStatus.PASSED);
		assertThat(examples(result)).isEmpty();
		assertThat(examples(result)).noneMatch(example ->
				MigrationErrorCodes.ENTITY_EXTRACTION_FAILED.equals(example.get("reason")));
		assertThat((List<?>) result.details().get("relationships")).hasSize(8);
		assertThat((List<Map<String, Object>>) result.details().get("relationships"))
				.allSatisfy(relationship -> assertThat(relationship.get("status")).isEqualTo("PASSED"));
	}

	@Test
	void fiveEntityModelStillReconcilesWithoutSubAddress() {
		EntityReconciliationAccumulator accumulator = new EntityReconciliationAccumulator();
		accumulator.beginBatch();
		List<ExecutionWorkItem> planned = new ArrayList<>();
		List<StagedEntityView> staged = new ArrayList<>();
		for (int index = 1; index <= 2; index++) {
			String addressLocation = "AL" + index;
			String point = "PT" + index;
			String address = "AD" + index;
			String location = "GL" + index;
			String geometry = "GM" + index;
			planned.add(item("AddressLocation", addressLocation, index, List.of()));
			planned.add(item("GeographicPointLocation", point, index,
					List.of(new WorkItemParent("AddressLocation", addressLocation))));
			planned.add(item("GeographicAddress", address, index,
					List.of(
							new WorkItemParent("AddressLocation", addressLocation),
							new WorkItemParent("GeographicLocation", location))));
			planned.add(item("GeographicLocation", location, index,
					List.of(new WorkItemParent("GeographicPointLocation", point))));
			planned.add(item("LocationGeometry", geometry, index,
					List.of(
							new WorkItemParent("GeographicPointLocation", point),
							new WorkItemParent("GeographicLocation", location))));
			staged.add(staged("AddressLocation", addressLocation, List.of()));
			staged.add(staged("GeographicPointLocation", point, List.of(link("AddressLocation", addressLocation))));
			staged.add(staged("GeographicAddress", address, List.of(
					link("AddressLocation", addressLocation), link("GeographicLocation", location))));
			staged.add(staged("GeographicLocation", location, List.of(link("GeographicPointLocation", point))));
			staged.add(staged("LocationGeometry", geometry, List.of(
					link("GeographicPointLocation", point), link("GeographicLocation", location))));
		}
		EntityRunFacts.foldLocator(accumulator, EntityBatchLocator.write(new ExecutionBatchPlan(1, planned)));
		accumulator.markTargetResultsKnown();
		for (StagedEntityView view : staged) {
			EntityRunFacts.foldStaged(accumulator, view);
		}
		EntityRunFacts.foldError(
				accumulator,
				MigrationErrorCodes.ENTITY_EXTRACTION_FAILED,
				"ENTITY_EXTRACTION_FAILED",
				"1",
				Map.of("entityName", "GeographicSubAddress", "sourceIdentity", ""));

		EntityReconciliationResult result = accumulator.finish();

		assertThat(overall(result)).containsEntry("sourceEntityCount", 10L).containsEntry("plannedEntityCount", 10L);
		assertThat(result.differenceCount()).isZero();
		assertThat(result.status()).isEqualTo(ReconciliationStatus.PASSED);
		assertThat(result.details().get("entities")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
				.doesNotContainKey("GeographicSubAddress");
		assertThat(examples(result)).isEmpty();
		assertThat((List<Map<String, Object>>) result.details().get("relationships")).hasSize(6)
				.allSatisfy(relationship -> assertThat(relationship.get("status")).isEqualTo("PASSED"));
	}

	@Test
	void plannedEntityThatWasNotProducedStillFails() {
		EntityReconciliationAccumulator accumulator = new EntityReconciliationAccumulator();
		accumulator.beginBatch();
		ExecutionWorkItem planned = item("Child", "C1", 1, List.of(new WorkItemParent("Root", "R1")));
		EntityRunFacts.foldLocator(accumulator, EntityBatchLocator.write(new ExecutionBatchPlan(1, List.of(
				item("Root", "R1", 1, List.of()),
				planned))));
		accumulator.markTargetResultsKnown();
		EntityRunFacts.foldStaged(accumulator, staged("Root", "R1", List.of()));
		EntityRunFacts.foldError(
				accumulator,
				MigrationErrorCodes.ENTITY_EXTRACTION_FAILED,
				"ENTITY_PAYLOAD_NOT_FOUND",
				"C1",
				Map.of("entityName", "Child", "sourceIdentity", "C1"));
		EntityRunFacts.foldError(
				accumulator,
				MigrationErrorCodes.ENTITY_EXTRACTION_FAILED,
				"ENTITY_EXTRACTION_FAILED",
				"2",
				Map.of("entityName", "Child", "sourceIdentity", ""));

		EntityReconciliationResult result = accumulator.finish();

		assertThat(result.status()).isEqualTo(ReconciliationStatus.FAILED);
		assertThat(entity(result, "Child")).containsEntry("sourceCount", 1L)
				.containsEntry("plannedCount", 1L)
				.containsEntry("failureCount", 1L);
		assertThat(examples(result)).singleElement().satisfies(example -> {
			assertThat(example.get("entity")).isEqualTo("Child");
			assertThat(example.get("sourceIdentity")).isEqualTo("C1");
			assertThat(example.get("reason")).isEqualTo(MigrationErrorCodes.ENTITY_EXTRACTION_FAILED);
		});
	}

	private static ExecutionWorkItem item(
			String entityName,
			String sourceIdentity,
			long recordNumber,
			List<WorkItemParent> parents) {
		return new ExecutionWorkItem(entityName, sourceIdentity, recordNumber, entityName, sourceIdentity, parents);
	}

	private static StagedEntityView staged(
			String entityName,
			String sourceIdentity,
			List<StagedRelationshipView> relationships) {
		return new StagedEntityView(entityName, sourceIdentity, "T-" + sourceIdentity, relationships);
	}

	private static StagedRelationshipView link(String parentEntity, String parentSourceIdentity) {
		return new StagedRelationshipView(parentEntity, parentSourceIdentity, "T-" + parentSourceIdentity);
	}

	private static EntityReconciliationResult counts(Map<String, Integer> counts) {
		EntityReconciliationAccumulator accumulator = new EntityReconciliationAccumulator();
		for (Map.Entry<String, Integer> entry : counts.entrySet()) {
			for (int index = 0; index < entry.getValue(); index++) {
				accumulator.observe(EntityObservation.success(
						entry.getKey(),
						entry.getKey() + "-" + index,
						"T-" + entry.getKey() + "-" + index,
						List.of()));
			}
		}
		return accumulator.finish();
	}

	private static EntityReconciliationResult parentAndChild(RelationshipFact fact) {
		return parentAndChild(fact, EntityOutcome.SUCCESS);
	}

	private static EntityReconciliationResult parentAndChild(RelationshipFact fact, EntityOutcome childOutcome) {
		EntityReconciliationAccumulator accumulator = new EntityReconciliationAccumulator();
		accumulator.observe(EntityObservation.success("Parent", "P1", "TP", List.of()));
		String childTarget = childOutcome == EntityOutcome.SUCCESS ? "TC" : null;
		accumulator.observe(EntityObservation.outcome("Child", "C1", childOutcome, childTarget, List.of(fact)));
		return accumulator.finish();
	}

	private static EntityReconciliationResult twoChildren(RelationshipCardinality cardinality) {
		EntityReconciliationAccumulator accumulator = new EntityReconciliationAccumulator();
		accumulator.noteCardinality("Parent", "Child", cardinality.name());
		accumulator.observe(EntityObservation.success("Parent", "P1", "TP", List.of()));
		accumulator.observe(EntityObservation.success(
				"Child",
				"C1",
				"TC1",
				List.of(RelationshipFact.resolved("Parent", "P1", "TP", "TC1"))));
		accumulator.observe(EntityObservation.success(
				"Child",
				"C2",
				"TC2",
				List.of(RelationshipFact.resolved("Parent", "P1", "TP", "TC2"))));
		return accumulator.finish();
	}

	private static EntityReconciliationResult sample() {
		EntityReconciliationAccumulator accumulator = new EntityReconciliationAccumulator();
		accumulator.beginBatch();
		accumulator.observe(EntityObservation.success("Alpha", "A1", "TA", List.of()));
		accumulator.observe(EntityObservation.outcome("Beta", "B1", EntityOutcome.FAILURE, null, List.of()));
		accumulator.observe(EntityObservation.success(
				"Gamma",
				"G1",
				"TG",
				List.of(RelationshipFact.resolved("Alpha", "A1", "TA", "TG"))));
		return accumulator.finish();
	}

	private static EntityReconciliationResult reconcileCsv(Path tempDir, Map<String, Object> sourceConfig) {
		EntityRelationshipPlanSource plans = planSource(tempDir);
		EntityRelationshipMapping mapping = placeModel();
		List<ExecutionBatchPlan> batches = new ArrayList<>();
		List<EntityItemFailure> failures = new ArrayList<>();
		plans.consume(sourceConfig, mapping, placeRules(), 10, batches::add, failures::add);
		assertThat(failures).isEmpty();
		EntityReconciliationAccumulator accumulator = new EntityReconciliationAccumulator();
		for (ExecutionBatchPlan batch : batches) {
			accumulator.beginBatch();
			for (ExecutionWorkItem item : batch.workItems()) {
				List<RelationshipFact> links = new ArrayList<>();
				for (WorkItemParent parent : item.parents()) {
					links.add(RelationshipFact.resolved(
							parent.entityName(),
							parent.sourceIdentity(),
							targetId(parent.entityName(), parent.sourceIdentity()),
							targetId(item.entityName(), item.sourceIdentity())));
				}
				accumulator.observe(EntityObservation.success(
						item.entityName(),
						item.sourceIdentity(),
						targetId(item.entityName(), item.sourceIdentity()),
						links));
			}
		}
		return accumulator.finish();
	}

	private static String targetId(String entityName, String sourceIdentity) {
		return "T-" + entityName + ":" + sourceIdentity;
	}

	private static EntityRelationshipPlanSource planSource(Path tempDir) {
		FileSystemArtifactStorage files = new FileSystemArtifactStorage(tempDir);
		files.initializeRoot();
		TransformationEngine transformations = new TransformationEngine();
		MappingEngine mappingEngine = new MappingEngine(transformations);
		EntityExtractor extractor = new EntityExtractor(mappingEngine);
		CsvSourceConnector csv = new CsvSourceConnector(new DefaultArtifactStorage(files));
		return new EntityRelationshipPlanSource(
				new EntityCsvAccess(csv, extractor, new MultiFileEntitySource(csv, extractor)),
				new RelationshipResolver(),
				new LogicalMigrationScopeBuilder(),
				new ExecutionPlanner());
	}

	private static Map<String, Object> singleFile(Path tempDir) throws Exception {
		Files.writeString(tempDir.resolve("single.csv"), """
				location_id,location_name,address_id,address_name,address_location_id,site_id,site_name,site_address_id
				L001,Main,A001,One,L001,S001,S1,A001
				""");
		Map<String, Object> config = new LinkedHashMap<>();
		config.put("provider", "FILE_SYSTEM");
		config.put("objectKey", "single.csv");
		config.put("fileFormat", "CSV");
		config.put("hasHeader", true);
		return config;
	}

	private static Map<String, Object> multiFile(Path tempDir) throws Exception {
		Files.writeString(tempDir.resolve("location.csv"), """
				location_id,location_name
				L001,Main
				""");
		Files.writeString(tempDir.resolve("address.csv"), """
				address_id,address_name,address_location_id
				A001,One,L001
				""");
		Files.writeString(tempDir.resolve("site.csv"), """
				site_id,site_name,site_address_id
				S001,S1,A001
				""");
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

	private static EntityRelationshipMapping placeModel() {
		return new EntityRelationshipMapping(
				"Location",
				List.of(
						new EntityDefinition("Location", "geographic_location", "location_id", "id"),
						new EntityDefinition("Address", "geographic_address", "address_id", "id"),
						new EntityDefinition("Site", "geographic_site", "site_id", "id")),
				List.of(
						new RelationshipDefinition(
								"Location",
								"Address",
								RelationshipCardinality.ONE_TO_MANY,
								List.of(new RelationshipKey("location_id", "address_location_id"))),
						new RelationshipDefinition(
								"Address",
								"Site",
								RelationshipCardinality.ONE_TO_MANY,
								List.of(new RelationshipKey("address_id", "site_address_id")))));
	}

	private static List<MigrationMappingRule> placeRules() {
		return List.of(
				rule("Location", 1, "location_name"),
				rule("Address", 2, "address_name"),
				rule("Site", 3, "site_name"));
	}

	private static MigrationMappingRule rule(String entity, int sequence, String sourceField) {
		MigrationMappingRule rule = new MigrationMappingRule();
		rule.setEntityName(entity);
		rule.setSequenceNo(sequence);
		rule.setSourceField(sourceField);
		rule.setTargetField("name");
		rule.setTransformationType(TransformationType.DIRECT);
		rule.setMandatory(false);
		rule.setEnabled(true);
		rule.setConfiguration(Map.of());
		return rule;
	}

	private static MigrationRun run(MigrationRunStatus status) {
		MigrationRun run = new MigrationRun();
		run.setStatus(status);
		return run;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> overall(EntityReconciliationResult result) {
		return (Map<String, Object>) result.details().get("overall");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> entity(EntityReconciliationResult result, String name) {
		return (Map<String, Object>) ((Map<String, Object>) result.details().get("entities")).get(name);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> relationship(EntityReconciliationResult result, String parent, String child) {
		List<Map<String, Object>> relationships = (List<Map<String, Object>>) result.details().get("relationships");
		return relationships.stream()
				.filter(item -> parent.equals(item.get("parentEntity")) && child.equals(item.get("childEntity")))
				.findFirst()
				.orElseThrow();
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> examples(EntityReconciliationResult result) {
		return (List<Map<String, Object>>) result.details().get("examples");
	}
}
