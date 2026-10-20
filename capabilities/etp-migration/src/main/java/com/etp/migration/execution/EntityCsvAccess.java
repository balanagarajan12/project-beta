package com.etp.migration.execution;

import com.etp.migration.connector.source.InvalidSourceConfigurationException;
import com.etp.migration.connector.source.csv.CsvFileSourceConfiguration;
import com.etp.migration.connector.source.csv.CsvSourceConnector;
import com.etp.migration.connector.source.csv.MultiFileEntitySource;
import com.etp.migration.connector.source.csv.SourceLayout;
import com.etp.migration.connector.source.csv.SourceLayoutConfiguration;
import com.etp.migration.connector.source.csv.SourceLayoutType;
import com.etp.migration.connector.source.sftp.SftpCsvSourceConfiguration;
import com.etp.migration.connector.source.sftp.SftpCsvSourceConnector;
import com.etp.migration.entity.MigrationMappingRule;
import com.etp.migration.extraction.EntityExtractor;
import com.etp.migration.mapping.EntityRelationshipMapping;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class EntityCsvAccess {

	private final CsvSourceConnector csvSourceConnector;
	private final EntityExtractor entityExtractor;
	private final MultiFileEntitySource multiFileEntitySource;
	private final SftpCsvSourceConnector sftpCsvSourceConnector;

	public EntityCsvAccess(
			CsvSourceConnector csvSourceConnector,
			EntityExtractor entityExtractor,
			MultiFileEntitySource multiFileEntitySource) {
		this(csvSourceConnector, entityExtractor, multiFileEntitySource, null);
	}

	@Autowired
	public EntityCsvAccess(
			CsvSourceConnector csvSourceConnector,
			EntityExtractor entityExtractor,
			MultiFileEntitySource multiFileEntitySource,
			SftpCsvSourceConnector sftpCsvSourceConnector) {
		this.csvSourceConnector = csvSourceConnector;
		this.entityExtractor = entityExtractor;
		this.multiFileEntitySource = multiFileEntitySource;
		this.sftpCsvSourceConnector = sftpCsvSourceConnector;
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
		if (SftpCsvSourceConfiguration.declaresSftp(sourceConfiguration)) {
			Object layoutType = sourceConfiguration.get("layoutType");
			if (layoutType != null && "MULTI_FILE".equalsIgnoreCase(layoutType.toString().trim())) {
				throw new InvalidSourceConfigurationException("SFTP source supports a single remote CSV file");
			}
			if (sftpCsvSourceConnector == null) {
				throw new InvalidSourceConfigurationException("SFTP source connector is not available");
			}
			SftpCsvSourceConfiguration sftp = SftpCsvSourceConfiguration.from(sourceConfiguration);
			return new EntityCsvRecords(
					entityExtractor,
					multiFileEntitySource,
					null,
					() -> sftpCsvSourceConnector.readAll(sftp),
					mapping,
					rules);
		}
		Optional<SourceLayout> declared = SourceLayoutConfiguration.parseIfDeclared(sourceConfiguration);
		if (declared.isPresent() && declared.get().type() == SourceLayoutType.MULTI_FILE) {
			return new EntityCsvRecords(
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
				entityExtractor,
				multiFileEntitySource,
				null,
				() -> csvSourceConnector.readAll(single),
				mapping,
				rules);
	}
}
