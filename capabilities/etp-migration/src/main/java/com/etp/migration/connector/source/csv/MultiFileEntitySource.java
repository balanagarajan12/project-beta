package com.etp.migration.connector.source.csv;

import com.etp.migration.connector.source.InvalidSourceConfigurationException;
import com.etp.migration.entity.MigrationMappingRule;
import com.etp.migration.extraction.EntityExtraction;
import com.etp.migration.extraction.EntityExtractionFailure;
import com.etp.migration.extraction.EntityExtractor;
import com.etp.migration.extraction.EntityRecord;
import com.etp.migration.mapping.EntityDefinition;
import com.etp.migration.mapping.EntityRelationshipMapping;
import com.etp.migration.mapping.RelationshipDefinition;
import com.etp.migration.connector.source.csv.SourceLayout.SourceFileBinding;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Opens one configured CSV at a time and extracts the entity that file is mapped to.
 * CSV parsing stays in {@link CsvSourceConnector}. Relationship resolution is not done here.
 */
@Component
public class MultiFileEntitySource {

	private final CsvSourceConnector csvSourceConnector;
	private final EntityExtractor entityExtractor;

	public MultiFileEntitySource(CsvSourceConnector csvSourceConnector, EntityExtractor entityExtractor) {
		this.csvSourceConnector = csvSourceConnector;
		this.entityExtractor = entityExtractor;
	}

	public Stream<EntityRecord> open(
			SourceLayout layout,
			String entityName,
			EntityRelationshipMapping mapping,
			List<MigrationMappingRule> rules,
			Consumer<EntityExtractionFailure> extractionFailures) {
		if (layout == null || layout.type() != SourceLayoutType.MULTI_FILE) {
			throw new InvalidSourceConfigurationException("MULTI_FILE layout is required");
		}
		SourceFileBinding binding = layout.files().stream()
				.filter(file -> entityName.equals(file.entityName()))
				.findFirst()
				.orElseThrow(() -> new InvalidSourceConfigurationException(
						"No source file is configured for entity '" + entityName + "'"));
		EntityDefinition entity = mapping.entities().stream()
				.filter(candidate -> entityName.equals(candidate.name()))
				.findFirst()
				.orElseThrow(() -> new InvalidSourceConfigurationException(
						"Source file entity '" + entityName
								+ "' is not defined in the migration mapping configuration."));
		List<RelationshipDefinition> touching = mapping.relationships().stream()
				.filter(relationship -> entityName.equals(relationship.parent()) || entityName.equals(relationship.child()))
				.toList();
		EntityRelationshipMapping scoped = new EntityRelationshipMapping(entity.name(), List.of(entity), touching);
		com.etp.migration.processing.PipelineTiming.increment("sourceFileOpens");
		Stream<com.etp.migration.processing.SourceRecord> physical = csvSourceConnector.readAll(binding.csv());
		return physical.flatMap(source -> {
			EntityExtraction extraction = entityExtractor.extract(source, scoped, rules);
			if (extractionFailures != null) {
				extraction.failures().forEach(extractionFailures);
			}
			return extraction.records().stream();
		});
	}
}
