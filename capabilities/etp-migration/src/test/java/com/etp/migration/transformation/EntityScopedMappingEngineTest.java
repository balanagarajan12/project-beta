package com.etp.migration.transformation;

import static org.assertj.core.api.Assertions.assertThat;

import com.etp.migration.entity.MigrationMappingRule;
import com.etp.migration.entity.TransformationType;
import com.etp.migration.processing.SourceRecord;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class EntityScopedMappingEngineTest {

	private final MappingEngine engine = new MappingEngine(new TransformationEngine());

	@ParameterizedTest
	@MethodSource("models")
	void rulesForOneEntityAreNotAppliedToAnother(Model model) {
		List<MigrationMappingRule> rules = List.of(
				rule(4, model.child(), model.childSource(), model.childTarget(), false),
				rule(2, model.root(), model.rootSource(), model.rootTarget(), true),
				rule(3, model.child(), model.childSource(), model.childTarget(), true),
				rule(1, model.root(), model.rootPlainSource(), model.rootPlainTarget(), true));

		assertThat(engine.rulesForEntity(rules, model.root()))
				.extracting(MigrationMappingRule::getSequenceNo)
				.containsExactly(1, 2);
		assertThat(engine.rulesForEntity(rules, model.child()))
				.extracting(MigrationMappingRule::getSequenceNo)
				.containsExactly(3);

		SourceRecord row = new SourceRecord(1L, Map.of(
				model.rootSource(), "  ada ",
				model.rootPlainSource(), "C001",
				model.childSource(), "  100 "));

		assertThat(engine.mapEntity(row, rules, model.root()).fields())
				.containsExactlyInAnyOrderEntriesOf(Map.of(
						model.rootPlainTarget(), "C001",
						model.rootTarget(), "ada"));
		assertThat(engine.mapEntity(row, rules, model.child()).fields())
				.containsExactlyInAnyOrderEntriesOf(Map.of(model.childTarget(), "100"));
		assertThat(engine.mapEntity(row, rules, model.grandchild()).fields()).isEmpty();
	}

	@ParameterizedTest
	@MethodSource("models")
	void flatMappingStillAppliesEveryEnabledRule(Model model) {
		List<MigrationMappingRule> rules = List.of(
				legacy(1, model.rootPlainSource(), model.rootPlainTarget(), TransformationType.DIRECT),
				legacy(2, model.rootSource(), model.rootTarget(), TransformationType.TRIM));
		SourceRecord row = new SourceRecord(1L, Map.of(
				model.rootPlainSource(), "C001",
				model.rootSource(), "  ada "));

		assertThat(engine.map(row, rules).fields())
				.containsExactlyInAnyOrderEntriesOf(Map.of(
						model.rootPlainTarget(), "C001",
						model.rootTarget(), "ada"));
	}

	static Stream<Model> models() {
		return Stream.of(
				new Model(
						"Customer",
						"Account",
						"Subscription",
						"customer_name",
						"name",
						"customer_id",
						"customerId",
						"account_number",
						"accountNumber"),
				new Model(
						"Product",
						"Variant",
						"Price",
						"product_name",
						"name",
						"product_id",
						"productId",
						"variant_sku",
						"sku"));
	}

	private static MigrationMappingRule rule(
			int sequence,
			String entity,
			String source,
			String target,
			boolean enabled) {
		MigrationMappingRule rule = legacy(sequence, source, target, TransformationType.TRIM);
		rule.setEntityName(entity);
		rule.setEnabled(enabled);
		return rule;
	}

	private static MigrationMappingRule legacy(
			int sequence,
			String source,
			String target,
			TransformationType type) {
		MigrationMappingRule rule = new MigrationMappingRule();
		rule.setSequenceNo(sequence);
		rule.setSourceField(source);
		rule.setTargetField(target);
		rule.setTransformationType(type);
		rule.setEnabled(true);
		rule.setConfiguration(Map.of());
		return rule;
	}

	record Model(
			String root,
			String child,
			String grandchild,
			String rootSource,
			String rootTarget,
			String rootPlainSource,
			String rootPlainTarget,
			String childSource,
			String childTarget
	) {
	}
}
