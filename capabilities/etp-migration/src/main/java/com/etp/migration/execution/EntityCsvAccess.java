package com.etp.migration.execution;

import com.etp.migration.connector.source.InvalidSourceConfigurationException;
import com.etp.migration.connector.source.csv.CsvFileSourceConfiguration;
import com.etp.migration.connector.source.csv.CsvPositionedFile;
import com.etp.migration.connector.source.csv.SourceByteProgress;
import com.etp.migration.connector.source.csv.CsvSourceConnector;
import com.etp.migration.connector.source.csv.MultiFileEntitySource;
import com.etp.migration.connector.source.csv.SourceLayout;
import com.etp.migration.connector.source.csv.SourceLayoutConfiguration;
import com.etp.migration.connector.source.csv.SourceLayoutType;
import com.etp.migration.connector.source.sftp.SftpCsvSourceConfiguration;
import com.etp.migration.connector.source.sftp.SftpCsvSourceConnector;
import com.etp.migration.connector.source.sftp.SftpSourceCache;
import com.etp.migration.connector.storage.FileSystemArtifactStorage;
import com.etp.migration.entity.MigrationMappingRule;
import com.etp.migration.extraction.EntityExtractor;
import com.etp.migration.mapping.EntityRelationshipMapping;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
	private SftpSourceCache sftpSourceCache;
	private FileSystemArtifactStorage fileStorage;

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

	@Autowired(required = false)
	public void setSftpSourceCache(SftpSourceCache sftpSourceCache) {
		this.sftpSourceCache = sftpSourceCache;
	}

	@Autowired(required = false)
	public void setFileStorage(FileSystemArtifactStorage fileStorage) {
		this.fileStorage = fileStorage;
	}

	public EntityCsvRecords open(
			Map<String, Object> sourceConfiguration,
			EntityRelationshipMapping mapping,
			java.util.List<MigrationMappingRule> rules) {
		return open(sourceConfiguration, mapping, rules, null);
	}

	public EntityCsvRecords open(
			Map<String, Object> sourceConfiguration,
			EntityRelationshipMapping mapping,
			java.util.List<MigrationMappingRule> rules,
			SourceByteProgress progress) {
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
			if (sftpSourceCache != null && CsvPositionedFile.positionable(sftp.delimiter(), sftp.quoteCharacter())) {
				CsvPositionedFile cached = sftpSourceCache.materialize(sftp, progress);
				return new EntityCsvRecords(
						entityExtractor,
						multiFileEntitySource,
						null,
						cached::readAll,
						cached,
						mapping,
						rules);
			}
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
		CsvPositionedFile positioned = position(single, progress);
		if (positioned != null) {
			return new EntityCsvRecords(
					entityExtractor,
					multiFileEntitySource,
					null,
					positioned::readAll,
					positioned,
					mapping,
					rules);
		}
		return new EntityCsvRecords(
				entityExtractor,
				multiFileEntitySource,
				null,
				() -> csvSourceConnector.readAll(single),
				mapping,
				rules);
	}

	private CsvPositionedFile position(CsvFileSourceConfiguration config, SourceByteProgress progress) {
		if (fileStorage == null || !CsvPositionedFile.positionable(config.delimiter(), config.quoteCharacter())) {
			return null;
		}
		Path csv = fileStorage.path(config.objectKey());
		try {
			long size = Files.size(csv);
			FileTime modified = Files.getLastModifiedTime(csv);
			String hash = digest(csv.toAbsolutePath().toString() + "\n" + size + "\n" + modified.toMillis()
					+ "\n" + config.delimiter() + "\n" + config.quoteCharacter()
					+ "\n" + config.hasHeader() + "\n" + config.encoding().name());
			Path index = fileStorage.root().resolve("etp-source-cache").resolve("file").resolve(hash + ".idx");
			return CsvPositionedFile.open(
					csv,
					index,
					config.encoding(),
					config.delimiter(),
					config.quoteCharacter(),
					config.hasHeader(),
					progress);
		} catch (java.io.IOException ex) {
			throw new com.etp.migration.processing.BatchProcessingException(
					com.etp.migration.processing.MigrationErrorCodes.SOURCE_READ_ERROR,
					"Unable to read source batch",
					ex);
		}
	}

	private static String digest(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is not available", ex);
		}
	}
}
