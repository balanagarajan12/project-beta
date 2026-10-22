package com.etp.migration.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.etp.migration.connector.source.csv.CsvSourceConnector;
import com.etp.migration.connector.source.csv.MultiFileEntitySource;
import com.etp.migration.connector.storage.DefaultArtifactStorage;
import com.etp.migration.connector.storage.FileSystemArtifactStorage;
import com.etp.migration.extraction.EntityExtractor;
import com.etp.migration.identity.EntityIdentity;
import com.etp.migration.mapping.EntityRelationshipConfiguration;
import com.etp.migration.processing.PipelineTiming;
import com.etp.migration.transformation.MappingEngine;
import com.etp.migration.transformation.TransformationEngine;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PositionedEntityReadTest {

	@TempDir
	Path tempDir;

	@AfterEach
	void releaseTiming() {
		PipelineTiming.bind(null);
	}

	@Test
	void batchMatchingReadsOnlyTheSourceRowsNamedByTheWorkItems() throws Exception {
		Path file = tempDir.resolve("addresses.csv");
		StringBuilder csv = new StringBuilder("id,city\n");
		for (int row = 1; row <= 500; row++) {
			csv.append("A").append(row).append(",city-").append(row).append('\n');
		}
		Files.writeString(file, csv.toString(), StandardCharsets.UTF_8);
		FileSystemArtifactStorage storage = new FileSystemArtifactStorage(tempDir);
		storage.initializeRoot();
		EntityCsvAccess access = new EntityCsvAccess(
				new CsvSourceConnector(new DefaultArtifactStorage(storage)),
				new EntityExtractor(new MappingEngine(new TransformationEngine())),
				new MultiFileEntitySource(
						new CsvSourceConnector(new DefaultArtifactStorage(storage)),
						new EntityExtractor(new MappingEngine(new TransformationEngine()))));
		access.setFileStorage(storage);
		var mapping = EntityRelationshipConfiguration.parse(Map.of(
				"modelType", "ENTITY_RELATIONSHIP",
				"rootEntity", "Address",
				"entities", List.of(Map.of(
						"entity", "Address",
						"targetEntity", "geographic_address",
						"sourceKey", "id",
						"targetKey", "id")),
				"relationships", List.of()));

		PipelineTiming timing = new PipelineTiming();
		PipelineTiming.bind(timing);
		var records = access.open(Map.of(
				"provider", "FILE_SYSTEM",
				"fileFormat", "CSV",
				"objectKey", "addresses.csv",
				"hasHeader", true), mapping, List.of());
		var matched = records.matching(Set.of(new EntityIdentity("Address", "A480")), new long[] {480L});

		assertThat(matched).containsOnlyKeys(new EntityIdentity("Address", "A480"));
		assertThat(matched.get(new EntityIdentity("Address", "A480")).sourceRecordNumber()).isEqualTo(480L);
		assertThat(timing.counter("sourceRowsParsed")).isEqualTo(1L);
		assertThat(timing.counter("sourceFileOpens")).isEqualTo(1L);
	}
}
