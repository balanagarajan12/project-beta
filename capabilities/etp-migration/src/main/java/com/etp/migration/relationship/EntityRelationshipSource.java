package com.etp.migration.relationship;

import com.etp.migration.connector.source.InvalidSourceConfigurationException;
import com.etp.migration.connector.source.csv.MultiFileEntitySource;
import com.etp.migration.connector.source.csv.SourceLayout;
import com.etp.migration.connector.source.csv.SourceLayoutConfiguration;
import com.etp.migration.connector.source.csv.SourceLayoutType;
import com.etp.migration.entity.MigrationMappingRule;
import com.etp.migration.extraction.EntityExtractionFailure;
import com.etp.migration.mapping.EntityRelationshipMapping;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Reads configured CSV files as streams, extracts one entity per file, then resolves relationships.
 * The batch worker is not involved. Legacy single-file execution is unchanged.
 */
@Component
public class EntityRelationshipSource {

	private final MultiFileEntitySource multiFileEntitySource;
	private final RelationshipResolver relationshipResolver;

	public EntityRelationshipSource(
			MultiFileEntitySource multiFileEntitySource,
			RelationshipResolver relationshipResolver) {
		this.multiFileEntitySource = multiFileEntitySource;
		this.relationshipResolver = relationshipResolver;
	}

	public SourceRelationshipResult resolve(
			Map<String, Object> sourceConfiguration,
			EntityRelationshipMapping mapping,
			List<MigrationMappingRule> rules) {
		SourceLayout layout = SourceLayoutConfiguration.parse(sourceConfiguration);
		if (layout.type() != SourceLayoutType.MULTI_FILE) {
			throw new InvalidSourceConfigurationException("MULTI_FILE layout is required");
		}
		List<EntityExtractionFailure> extractionFailures = new ArrayList<>();
		Set<String> reported = new HashSet<>();
		RelationshipResolution resolution = relationshipResolver.resolve(mapping, entityName -> multiFileEntitySource.open(
				layout,
				entityName,
				mapping,
				rules,
				reported.add(entityName) ? extractionFailures::add : ignored -> {
				}));
		return new SourceRelationshipResult(extractionFailures, resolution);
	}
}
