package com.etp.migration.execution;

import com.etp.migration.extraction.EntityExtraction;
import com.etp.migration.extraction.EntityExtractionFailure;
import com.etp.migration.extraction.EntityExtractor;
import com.etp.migration.extraction.EntityRecord;
import com.etp.migration.identity.EntityIdentity;
import com.etp.migration.connector.source.csv.CsvPositionedFile;
import com.etp.migration.connector.source.csv.MultiFileEntitySource;
import com.etp.migration.connector.source.csv.SourceLayout;
import com.etp.migration.entity.MigrationMappingRule;
import com.etp.migration.mapping.EntityRelationshipMapping;
import com.etp.migration.processing.SourceRecord;
import com.etp.migration.relationship.EntityRecordSource;
import java.util.ArrayList;
import java.util.OptionalLong;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.function.Supplier;

/**
 * Opens configured CSV files as entity records. Each call reads the files again.
 * {@link #matching(Set)} keeps only the requested identities.
 */
public final class EntityCsvRecords {

	private final EntityExtractor entityExtractor;
	private final MultiFileEntitySource multiFileEntitySource;
	private final SourceLayout layout;
	private final Supplier<Stream<SourceRecord>> singleFile;
	private final CsvPositionedFile positioned;
	private final EntityRelationshipMapping mapping;
	private final List<MigrationMappingRule> rules;
	private final List<EntityExtractionFailure> extractionFailures = new ArrayList<>();
	private final Set<String> seenFailures = new HashSet<>();

	EntityCsvRecords(
			EntityExtractor entityExtractor,
			MultiFileEntitySource multiFileEntitySource,
			SourceLayout layout,
			Supplier<Stream<SourceRecord>> singleFile,
			EntityRelationshipMapping mapping,
			List<MigrationMappingRule> rules) {
		this(entityExtractor, multiFileEntitySource, layout, singleFile, null, mapping, rules);
	}

	EntityCsvRecords(
			EntityExtractor entityExtractor,
			MultiFileEntitySource multiFileEntitySource,
			SourceLayout layout,
			Supplier<Stream<SourceRecord>> singleFile,
			CsvPositionedFile positioned,
			EntityRelationshipMapping mapping,
			List<MigrationMappingRule> rules) {
		this.entityExtractor = entityExtractor;
		this.multiFileEntitySource = multiFileEntitySource;
		this.layout = layout;
		this.singleFile = singleFile;
		this.positioned = positioned;
		this.mapping = mapping;
		this.rules = rules;
	}

	boolean isSingleFile() {
		return layout == null && singleFile != null;
	}

	/**
	 * Data rows in a single positioned CSV. Empty when this source has no row index,
	 * including multi-file layouts.
	 */
	OptionalLong physicalRowCount() {
		if (positioned == null) {
			return OptionalLong.empty();
		}
		return OptionalLong.of(positioned.recordCount());
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
		return matching(wanted, null);
	}

	Map<EntityIdentity, EntityRecord> matching(Set<EntityIdentity> wanted, long[] sourceRecordNumbers) {
		Map<EntityIdentity, EntityRecord> kept = new LinkedHashMap<>();
		if (wanted == null || wanted.isEmpty()) {
			return kept;
		}
		if (positioned != null && sourceRecordNumbers != null && sourceRecordNumbers.length > 0) {
			com.etp.migration.processing.PipelineTiming.increment("sourceFileOpens");
			try (Stream<SourceRecord> rows = positioned.readRecordNumbers(sourceRecordNumbers)) {
				rows.flatMap(this::extractAll).forEach(record -> keep(kept, wanted, record));
			}
			return kept;
		}
		try (Stream<EntityRecord> records = all()) {
			records.forEach(record -> keep(kept, wanted, record));
		}
		return kept;
	}

	private static void keep(
			Map<EntityIdentity, EntityRecord> kept,
			Set<EntityIdentity> wanted,
			EntityRecord record) {
		if (record == null || record.sourceIdentity() == null) {
			return;
		}
		EntityIdentity identity = new EntityIdentity(record.entityName(), record.sourceIdentity());
		if (wanted.contains(identity)) {
			kept.putIfAbsent(identity, record);
		}
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
		return singleFile.get();
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
