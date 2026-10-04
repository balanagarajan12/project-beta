package com.etp.migration.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.etp.migration.connector.source.InvalidSourceConfigurationException;
import com.etp.migration.entity.MigrationDefinition;
import com.etp.migration.entity.MigrationMapping;
import com.etp.migration.entity.MigrationSource;
import com.etp.migration.migration.dto.CreateMigrationSourceRequest;
import com.etp.migration.migration.dto.MigrationSourceResponse;
import com.etp.migration.repository.MigrationDefinitionRepository;
import com.etp.migration.repository.MigrationSourceRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MigrationSourceServiceTest {

	@Mock
	private MigrationDefinitionRepository migrationDefinitionRepository;

	@Mock
	private MigrationSourceRepository migrationSourceRepository;

	private MigrationSourceService service;

	@BeforeEach
	void setUp() {
		service = new MigrationSourceService(migrationDefinitionRepository, migrationSourceRepository);
	}

	@Test
	void createAppliesEnabledDefault() {
		UUID migrationId = UUID.randomUUID();
		MigrationDefinition definition = new MigrationDefinition();
		definition.setId(migrationId);
		when(migrationDefinitionRepository.findById(migrationId)).thenReturn(Optional.of(definition));
		when(migrationSourceRepository.save(any(MigrationSource.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		CreateMigrationSourceRequest request = new CreateMigrationSourceRequest(
				"address-csv", "FILE", null, null);

		MigrationSourceResponse response = service.create(migrationId, request);

		assertThat(response.enabled()).isTrue();
		assertThat(response.configuration()).isEmpty();
		ArgumentCaptor<MigrationSource> captor = ArgumentCaptor.forClass(MigrationSource.class);
		verify(migrationSourceRepository).save(captor.capture());
		assertThat(captor.getValue().isEnabled()).isTrue();
	}

	@Test
	void createRejectsUnknownDefinition() {
		UUID migrationId = UUID.randomUUID();
		when(migrationDefinitionRepository.findById(migrationId)).thenReturn(Optional.empty());

		CreateMigrationSourceRequest request = new CreateMigrationSourceRequest(
				"address-csv", "FILE", null, null);

		assertThatThrownBy(() -> service.create(migrationId, request))
				.isInstanceOf(MigrationDefinitionNotFoundException.class);
	}

	@Test
	void createRejectsFileMappedToAnUnknownEntity() {
		UUID migrationId = UUID.randomUUID();
		MigrationDefinition definition = definition(migrationId, model("Invoice"));
		when(migrationDefinitionRepository.findById(migrationId)).thenReturn(Optional.of(definition));

		CreateMigrationSourceRequest request = new CreateMigrationSourceRequest(
				"files",
				"FILE",
				multiFile("Address", "address.csv"),
				true);

		assertThatThrownBy(() -> service.create(migrationId, request))
				.isInstanceOf(InvalidSourceConfigurationException.class)
				.hasMessage("Source file entity 'Address' is not defined in the migration mapping configuration.");
		verify(migrationSourceRepository, never()).save(any());
	}

	@Test
	void createPersistsAValidMultiFileLayout() {
		UUID migrationId = UUID.randomUUID();
		MigrationDefinition definition = definition(migrationId, model("Invoice"));
		when(migrationDefinitionRepository.findById(migrationId)).thenReturn(Optional.of(definition));
		when(migrationSourceRepository.save(any(MigrationSource.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		MigrationSourceResponse response = service.create(
				migrationId,
				new CreateMigrationSourceRequest("files", "FILE", multiFile("Invoice", "invoice.csv"), true));

		assertThat(response.configuration()).containsEntry("layoutType", "MULTI_FILE");
	}

	private static MigrationDefinition definition(UUID migrationId, Map<String, Object> mappingConfiguration) {
		MigrationMapping mapping = new MigrationMapping();
		mapping.setId(UUID.randomUUID());
		mapping.setConfiguration(mappingConfiguration);
		MigrationDefinition definition = new MigrationDefinition();
		definition.setId(migrationId);
		definition.setMapping(mapping);
		return definition;
	}

	private static Map<String, Object> model(String name) {
		Map<String, Object> entity = new LinkedHashMap<>();
		entity.put("name", name);
		entity.put("targetEntity", "target");
		entity.put("sourceKey", "source_id");
		entity.put("targetKey", "id");
		Map<String, Object> configuration = new LinkedHashMap<>();
		configuration.put("modelType", "ENTITY_RELATIONSHIP");
		configuration.put("rootEntity", name);
		configuration.put("entities", List.of(entity));
		configuration.put("relationships", List.of());
		return configuration;
	}

	private static Map<String, Object> multiFile(String entity, String file) {
		Map<String, Object> files = new LinkedHashMap<>();
		files.put(entity, Map.of("file", file));
		Map<String, Object> configuration = new LinkedHashMap<>();
		configuration.put("layoutType", "MULTI_FILE");
		configuration.put("files", files);
		return configuration;
	}
}
