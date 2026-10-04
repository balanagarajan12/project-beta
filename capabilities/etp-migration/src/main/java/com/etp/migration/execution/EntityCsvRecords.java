package com.etp.migration.execution;

import com.etp.migration.extraction.EntityExtraction;
import com.etp.migration.extraction.EntityExtractionFailure;
import com.etp.migration.extraction.EntityExtractor;
import com.etp.migration.extraction.EntityRecord;
import com.etp.migration.identity.EntityIdentity;
import com.etp.migration.connector.source.csv.CsvSourceConnector;
import com.etp.migration.connector.source.csv.MultiFileEntitySource;
import com.etp.migration.connector.source.csv.SourceLayout;
import com.etp.migration.connector.source.csv.CsvFileSourceConfiguration;
import com.etp.migration.entity.MigrationMappingRule;
import com.etp.migration.mapping.EntityRelationshipMapping;
import com.etp.migration.processing.SourceRecord;
import com.etp.migration.relationship.EntityRecordSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Opens configured CSV files as entity records. Each call reads the files again.
 * {@link #matching(Set)} keeps only the requested identities.
 */
public final class EntityCsvRecords {

	private final CsvSourceConnector csvSourceConnector;
	private final EntityExtractor entityExtractor;
	private final MultiFileEntitySource multiFileEntitySource;
	private final SourceLayout layout;
	private final CsvFileSourceConfiguration singleFile;
	private final EntityRelationshipMapping mapping;
	private final List<MigrationMappingRule> rules;
	private final List<EntityExtractionFailure> extractionFailures = new ArrayList<>();
	private final Set<String> seenFailures = new HashSet<>();

	EntityCsvRecords(
			CsvSourceConnector csvSourceConnector,
			EntityExtractor entityExtractor,
			MultiFileEntitySource multiFileEntitySource,
			SourceLayout layout,
			CsvFileSourceConfiguration singleFile,
			EntityRelationshipMapping mapping,
			List<MigrationMappingRule> rules) {
		this.csvSourceConnector = csvSourceConnector;
		this.entityExtractor = entityExtractor;
		this.multiFileEntitySource = multiFileEntitySource;
		this.layout = layout;
		this.singleFile = singleFile;
		this.mapping = mapping;
		this.rules = rules;
	}

	EntityRecordSource byEntity() {
		return this::open;
	}

	Stream<EntityRecord> all() {
		if (layout != null) {
			return mapping.entities().stream().flatMap(entity -> open(entity.name()));
		}
		return readSingleFile().flatMap(this::extractAll);
	}

	Map<EntityIdentity, EntityRecord> matching(Set<EntityIdentity> wanted) {
		Map<EntityIdentity, EntityRecord> kept = new LinkedHashMap<>();
		if (wanted == null || wanted.isEmpty()) {
			return kept;
		}
		try (Stream<EntityRecord> records = all()) {
			records.forEach(record -> {
				if (record == null || record.sourceIdentity() == null) {
					return;
				}
				EntityIdentity identity = new EntityIdentity(record.entityName(), record.sourceIdentity());
				if (wanted.contains(identity)) {
					kept.putIfAbsent(identity, record);
				}
			});
		}
		return kept;
	}

	List<EntityExtractionFailure> extractionFailures() {
		return List.copyOf(extractionFailures);
	}

	private Stream<EntityRecord> open(String entityName) {
		if (layout != null) {
			return multiFileEntitySource.open(layout, entityName, mapping, rules, this::recordFailure);
		}
		return readSingleFile().flatMap(source -> extractAll(source)
				.filter(record -> entityName.equals(record.entityName())));
	}

	private Stream<SourceRecord> readSingleFile() {
		com.etp.migration.processing.PipelineTiming.increment("sourceFileOpens");
		return csvSourceConnector.readAll(singleFile);
	}

	private Stream<EntityRecord> extractAll(SourceRecord source) {
		EntityExtraction extraction = entityExtractor.extract(source, mapping, rules);
		extraction.failures().forEach(this::recordFailure);
		return extraction.records().stream();
	}

	private void recordFailure(EntityExtractionFailure failure) {
		if (failure == null) {
			return;
		}
		String key = failure.entityName() + "\0" + failure.sourceRecordNumber() + "\0" + failure.message();
		if (seenFailures.add(key)) {
			extractionFailures.add(failure);
		}
	}
}
