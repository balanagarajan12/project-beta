package com.etp.migration.relationship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.etp.migration.connector.source.csv.CsvSourceConnector;
import com.etp.migration.connector.source.csv.MultiFileEntitySource;
import com.etp.migration.connector.source.csv.SourceLayout;
import com.etp.migration.connector.source.csv.SourceLayoutConfiguration;
import com.etp.migration.connector.storage.DefaultArtifactStorage;
import com.etp.migration.connector.storage.FileSystemArtifactStorage;
import com.etp.migration.entity.MigrationMappingRule;
import com.etp.migration.entity.TransformationType;
import com.etp.migration.extraction.EntityExtractor;
import com.etp.migration.extraction.EntityRecord;
import com.etp.migration.mapping.EntityDefinition;
import com.etp.migration.mapping.EntityRelationshipMapping;
import com.etp.migration.mapping.RelationshipCardinality;
import com.etp.migration.mapping.RelationshipDefinition;
import com.etp.migration.mapping.RelationshipKey;
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

class MultiFileRelationshipTest {

	@TempDir
	Path tempDir;

	private EntityRelationshipSource source;
	private MultiFileEntitySource files;

	@BeforeEach
	void setUp() {
		FileSystemArtifactStorage storage = new FileSystemArtifactStorage(tempDir);
		storage.initializeRoot();
		CsvSourceConnector csv = new CsvSourceConnector(new DefaultArtifactStorage(storage));
		EntityExtractor extractor = new EntityExtractor(new MappingEngine(new TransformationEngine()));
		files = new MultiFileEntitySource(csv, extractor);
		source = new EntityRelationshipSource(files, new RelationshipResolver());
	}

	@Test
	void extractsEachFileAsItsOwnEntityAndResolvesRelationships() throws Exception {
		Files.writeString(tempDir.resolve("customer.csv"), """
				customer_id,name
				C001,John
				C002,Mary
				""");
		Files.writeString(tempDir.resolve("account.csv"), """
				account_id,customer_id,number
				A001,C001,ACC001
				A002,C001,ACC002
				A003,C002,ACC003
				""");
		EntityRelationshipMapping mapping = mapping();
		List<MigrationMappingRule> rules = rules();
		SourceLayout layout = SourceLayoutConfiguration.parse(layout());

		try (var customers = files.open(layout, "Customer", mapping, rules, ignored -> { });
				var accounts = files.open(layout, "Account", mapping, rules, ignored -> { })) {
			assertThat(customers.map(EntityRecord::entityName).toList()).containsExactly("Customer", "Customer");
			assertThat(accounts.toList())
					.extracting(EntityRecord::entityName, EntityRecord::sourceIdentity)
					.containsExactly(
							tuple("Account", "A001"),
							tuple("Account", "A002"),
							tuple("Account", "A003"));
		}

		SourceRelationshipResult result = source.resolve(layout(), mapping, rules);

		assertThat(result.extractionFailures()).isEmpty();
		assertThat(result.resolution().failures()).isEmpty();
		assertThat(result.resolution().relationships())
				.extracting(ResolvedRelationship::parentIdentity, ResolvedRelationship::childIdentity)
				.containsExactly(
						tuple("C001", "A001"),
						tuple("C001", "A002"),
						tuple("C002", "A003"));
		assertThat(result.resolution().indexedParentCount()).isEqualTo(2);
	}

	@Test
	void keepsExtractionFailuresSeparateFromRelationshipFailures() throws Exception {
		Files.writeString(tempDir.resolve("customer.csv"), """
				customer_id,name
				C001,John
				""");
		Files.writeString(tempDir.resolve("account.csv"), """
				account_id,customer_id,number
				,C001,ACC001
				""");

		SourceRelationshipResult result = source.resolve(layout(), mapping(), rules());

		assertThat(result.resolution().relationships()).isEmpty();
		assertThat(result.resolution().failures()).isEmpty();
		assertThat(result.extractionFailures()).singleElement().satisfies(failure -> {
			assertThat(failure.entityName()).isEqualTo("Account");
			assertThat(failure.sourceKey()).isEqualTo("account_id");
			assertThat(failure.message()).contains("source record 1");
		});
	}

	private static Map<String, Object> layout() {
		Map<String, Object> files = new LinkedHashMap<>();
		files.put("Customer", Map.of("file", "customer.csv"));
		files.put("Account", Map.of("file", "account.csv"));
		Map<String, Object> configuration = new LinkedHashMap<>();
		configuration.put("layoutType", "MULTI_FILE");
		configuration.put("provider", "FILE_SYSTEM");
		configuration.put("fileFormat", "CSV");
		configuration.put("hasHeader", true);
		configuration.put("files", files);
		return configuration;
	}

	private static EntityRelationshipMapping mapping() {
		return new EntityRelationshipMapping(
				"Customer",
				List.of(
						new EntityDefinition("Customer", "customer", "customer_id", "id"),
						new EntityDefinition("Account", "account", "account_id", "id")),
				List.of(new RelationshipDefinition(
						"Customer",
						"Account",
						RelationshipCardinality.ONE_TO_MANY,
						List.of(new RelationshipKey("customer_id", "customer_id")))));
	}

	private static List<MigrationMappingRule> rules() {
		return List.of(
				rule(1, "Customer", "customer_id", "id"),
				rule(2, "Customer", "name", "name"),
				rule(3, "Account", "account_id", "id"),
				rule(4, "Account", "customer_id", "customerId"),
				rule(5, "Account", "number", "number"));
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
}
