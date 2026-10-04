package com.etp.migration.execution;

import com.etp.migration.connector.source.InvalidSourceConfigurationException;
import com.etp.migration.connector.source.csv.CsvFileSourceConfiguration;
import com.etp.migration.connector.source.csv.CsvSourceConnector;
import com.etp.migration.connector.source.csv.MultiFileEntitySource;
import com.etp.migration.connector.source.csv.SourceLayout;
import com.etp.migration.connector.source.csv.SourceLayoutConfiguration;
import com.etp.migration.connector.source.csv.SourceLayoutType;
import com.etp.migration.entity.MigrationMappingRule;
import com.etp.migration.extraction.EntityExtractor;
import com.etp.migration.mapping.EntityRelationshipMapping;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class EntityCsvAccess {

	private final CsvSourceConnector csvSourceConnector;
	private final EntityExtractor entityExtractor;
	private final MultiFileEntitySource multiFileEntitySource;

	public EntityCsvAccess(
			CsvSourceConnector csvSourceConnector,
			EntityExtractor entityExtractor,
			MultiFileEntitySource multiFileEntitySource) {
		this.csvSourceConnector = csvSourceConnector;
		this.entityExtractor = entityExtractor;
		this.multiFileEntitySource = multiFileEntitySource;
	}

	public EntityCsvRecords open(
			Map<String, Object> sourceConfiguration,
			EntityRelationshipMapping mapping,
			java.util.List<MigrationMappingRule> rules) {
		if (sourceConfiguration == null) {
			throw new InvalidSourceConfigurationException("Source configuration is required");
		}
		if (mapping == null) {
			throw new IllegalArgumentException("mapping is required");
		}
		Optional<SourceLayout> declared = SourceLayoutConfiguration.parseIfDeclared(sourceConfiguration);
		if (declared.isPresent() && declared.get().type() == SourceLayoutType.MULTI_FILE) {
			return new EntityCsvRecords(
					csvSourceConnector,
					entityExtractor,
					multiFileEntitySource,
					declared.get(),
					null,
					mapping,
					rules);
		}
		CsvFileSourceConfiguration single = declared.isPresent()
				? declared.get().files().get(0).csv()
				: CsvFileSourceConfiguration.from(sourceConfiguration);
		return new EntityCsvRecords(
				csvSourceConnector,
				entityExtractor,
				multiFileEntitySource,
				null,
				single,
				mapping,
				rules);
	}
}
