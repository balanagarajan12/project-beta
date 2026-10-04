package com.etp.migration.connector.source.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.etp.migration.connector.source.InvalidSourceConfigurationException;
import com.etp.migration.mapping.EntityRelationshipConfiguration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SourceLayoutConfigurationTest {

	@Test
	void parsesExplicitFileToEntityMapping() {
		Map<String, Object> files = new LinkedHashMap<>();
		files.put("Customer", Map.of("file", "customer.csv"));
		files.put("Account", Map.of("file", "account.csv"));
		SourceLayout layout = SourceLayoutConfiguration.parse(multiFile(files));

		assertThat(layout.type()).isEqualTo(SourceLayoutType.MULTI_FILE);
		assertThat(layout.files()).extracting(file -> file.entityName(), file -> file.csv().objectKey())
				.containsExactly(
						org.assertj.core.groups.Tuple.tuple("Customer", "customer.csv"),
						org.assertj.core.groups.Tuple.tuple("Account", "account.csv"));
	}

	@Test
	void rejectsFileMappedToAnUnknownEntity() {
		SourceLayout layout = SourceLayoutConfiguration.parse(multiFile(Map.of(
				"Address", Map.of("file", "address.csv"))));

		assertThatThrownBy(() -> SourceLayoutConfiguration.validateEntities(layout, model("Invoice")))
				.isInstanceOf(InvalidSourceConfigurationException.class)
				.hasMessage("Source file entity 'Address' is not defined in the migration mapping configuration.");
	}

	@Test
	void rejectsEmptyFilesUnsupportedLayoutAndMissingPath() {
		assertThatThrownBy(() -> SourceLayoutConfiguration.parse(Map.of("layoutType", "MULTI_FILE")))
				.isInstanceOf(InvalidSourceConfigurationException.class)
				.hasMessage("Source files are required for MULTI_FILE");
		assertThatThrownBy(() -> SourceLayoutConfiguration.parse(Map.of(
				"layoutType", "MULTI_FILE",
				"files", Map.of())))
				.isInstanceOf(InvalidSourceConfigurationException.class)
				.hasMessage("Source files are required for MULTI_FILE");
		assertThatThrownBy(() -> SourceLayoutConfiguration.parse(Map.of("layoutType", "XLSX")))
				.isInstanceOf(InvalidSourceConfigurationException.class)
				.hasMessageContaining("SINGLE_FILE")
				.hasMessageContaining("MULTI_FILE");
		assertThatThrownBy(() -> SourceLayoutConfiguration.parse(multiFile(Map.of(
				"Invoice", Map.of("delimiter", ",")))))
				.isInstanceOf(InvalidSourceConfigurationException.class)
				.hasMessage("Source file for entity 'Invoice' requires file");
	}

	@Test
	void rejectsDuplicateEntityMappings() {
		Map<String, Object> files = new LinkedHashMap<>();
		files.put("Invoice", Map.of("file", "one.csv"));
		files.put(" Invoice ", Map.of("file", "two.csv"));

		assertThatThrownBy(() -> SourceLayoutConfiguration.parse(multiFile(files)))
				.isInstanceOf(InvalidSourceConfigurationException.class)
				.hasMessage("Duplicate source file for entity 'Invoice'");
	}

	@Test
	void omittedLayoutStaysOnTheExistingSingleObjectPath() {
		Map<String, Object> configuration = new LinkedHashMap<>();
		configuration.put("provider", "FILE_SYSTEM");
		configuration.put("objectKey", "data.csv");
		configuration.put("fileFormat", "CSV");
		configuration.put("hasHeader", true);

		assertThat(SourceLayoutConfiguration.parseIfDeclared(configuration)).isEmpty();
		assertThat(CsvFileSourceConfiguration.from(configuration).objectKey()).isEqualTo("data.csv");
	}

	@Test
	void singleFileLayoutAcceptsFileAliasAndExistingObjectKey() {
		SourceLayout named = SourceLayoutConfiguration.parse(Map.of(
				"layoutType", "SINGLE_FILE",
				"file", "data.csv",
				"delimiter", ",",
				"header", true));
		assertThat(named.type()).isEqualTo(SourceLayoutType.SINGLE_FILE);
		assertThat(named.files()).singleElement().satisfies(file -> {
			assertThat(file.entityName()).isNull();
			assertThat(file.csv().objectKey()).isEqualTo("data.csv");
			assertThat(file.csv().hasHeader()).isTrue();
		});

		Map<String, Object> existing = new LinkedHashMap<>();
		existing.put("layoutType", "SINGLE_FILE");
		existing.put("provider", "FILE_SYSTEM");
		existing.put("objectKey", "samples/data.csv");
		existing.put("fileFormat", "CSV");
		assertThat(CsvFileSourceConfiguration.from(existing).objectKey()).isEqualTo("samples/data.csv");
		assertThat(SourceLayoutConfiguration.parse(existing).files().get(0).csv().objectKey())
				.isEqualTo("samples/data.csv");
	}

	private static Map<String, Object> multiFile(Map<String, Object> files) {
		Map<String, Object> configuration = new LinkedHashMap<>();
		configuration.put("layoutType", "MULTI_FILE");
		configuration.put("files", files);
		return configuration;
	}

	private static Map<String, Object> model(String name) {
		return EntityRelationshipConfiguration.toConfiguration(new com.etp.migration.mapping.EntityRelationshipMapping(
				name,
				List.of(new com.etp.migration.mapping.EntityDefinition(name, "target", "source_id", "id")),
				List.of()));
	}
}
