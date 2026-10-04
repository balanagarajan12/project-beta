package com.etp.migration.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.etp.migration.entity.LoadMode;
import com.etp.migration.entity.MigrationConfigStatus;
import com.etp.migration.entity.MigrationDefinition;
import com.etp.migration.entity.MigrationMapping;
import com.etp.migration.entity.MigrationMappingRule;
import com.etp.migration.entity.MigrationSource;
import com.etp.migration.connector.source.InvalidSourceConfigurationException;
import com.etp.migration.connector.source.MigrationSourceConnectorRegistry;
import com.etp.migration.connector.source.UnsupportedFileFormatException;
import com.etp.migration.connector.source.csv.CsvSourceConnector;
import com.etp.migration.connector.storage.ArtifactStorage;
import com.etp.migration.connector.target.InvalidPromotionConfigurationException;
import com.etp.migration.mapping.InvalidEntityRelationshipConfigurationException;
import com.etp.migration.migration.dto.CreateMigrationDefinitionRequest;
import com.etp.migration.migration.dto.MigrationDefinitionResponse;
import com.etp.migration.migration.dto.PagedResponse;
import com.etp.migration.repository.MigrationDefinitionRepository;
import com.etp.migration.repository.MigrationMappingRepository;
import com.etp.migration.repository.MigrationMappingRuleRepository;
import com.etp.migration.repository.MigrationSourceRepository;
import com.etp.migration.run.InvalidStateTransitionException;
import java.time.Instant;
import java.util.HashMap;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class MigrationDefinitionServiceTest {

	@Mock
	private MigrationDefinitionRepository migrationDefinitionRepository;

	@Mock
	private MigrationMappingRepository migrationMappingRepository;

	@Mock
	private MigrationMappingRuleRepository migrationMappingRuleRepository;

	@Mock
	private MigrationSourceRepository migrationSourceRepository;

	@Mock
	private ArtifactStorage artifactStorage;

	private MigrationDefinitionService service;

	@BeforeEach
	void setUp() {
		service = new MigrationDefinitionService(
				migrationDefinitionRepository,
				migrationMappingRepository,
				migrationMappingRuleRepository,
				migrationSourceRepository,
				new MigrationSourceConnectorRegistry(List.of(new CsvSourceConnector(artifactStorage))));
	}

	@Test
	void createAppliesDraftStatusAndDefaults() {
		when(migrationDefinitionRepository.existsByNameAndVersion("uam-address-import", "1.0.0")).thenReturn(false);
		when(migrationDefinitionRepository.save(any(MigrationDefinition.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		CreateMigrationDefinitionRequest request = new CreateMigrationDefinitionRequest(
				"uam-address-import",
				"1.0.0",
				null,
				"FILE",
				"UAM",
				null,
				null,
				null,
				null,
				null);

		MigrationDefinitionResponse response = service.create(request);

		assertThat(response.status()).isEqualTo(MigrationConfigStatus.DRAFT);
		assertThat(response.batchSize()).isEqualTo(10_000);
		assertThat(response.loadMode()).isEqualTo(LoadMode.BULK);
		assertThat(response.configuration()).isEmpty();
		assertThat(response.createdBy()).isEqualTo("system");
		assertThat(response.id()).isNotNull();

		ArgumentCaptor<MigrationDefinition> captor = ArgumentCaptor.forClass(MigrationDefinition.class);
		verify(migrationDefinitionRepository).save(captor.capture());
		assertThat(captor.getValue().getStatus()).isEqualTo(MigrationConfigStatus.DRAFT);
	}

	@Test
	void createRejectsUnknownMappingId() {
		UUID mappingId = UUID.randomUUID();
		when(migrationDefinitionRepository.existsByNameAndVersion("uam-address-import", "1.0.0")).thenReturn(false);
		when(migrationMappingRepository.findById(mappingId)).thenReturn(Optional.empty());

		CreateMigrationDefinitionRequest request = new CreateMigrationDefinitionRequest(
				"uam-address-import",
				"1.0.0",
				null,
				"FILE",
				"UAM",
				null,
				mappingId,
				null,
				null,
				null);

		assertThatThrownBy(() -> service.create(request))
				.isInstanceOf(InvalidMappingReferenceException.class)
				.hasMessageContaining(mappingId.toString());
		verify(migrationDefinitionRepository, never()).save(any());
	}

	@Test
	void createRejectsDuplicateNameAndVersion() {
		when(migrationDefinitionRepository.existsByNameAndVersion("uam-address-import", "1.0.0")).thenReturn(true);

		CreateMigrationDefinitionRequest request = new CreateMigrationDefinitionRequest(
				"uam-address-import",
				"1.0.0",
				null,
				"FILE",
				"UAM",
				null,
				null,
				null,
				null,
				null);

		assertThatThrownBy(() -> service.create(request))
				.isInstanceOf(DuplicateMigrationDefinitionException.class);
		verify(migrationDefinitionRepository, never()).save(any());
	}

	@Test
	void createTranslatesUniqueConstraintViolation() {
		when(migrationDefinitionRepository.existsByNameAndVersion("uam-address-import", "1.0.0")).thenReturn(false);
		when(migrationDefinitionRepository.save(any(MigrationDefinition.class)))
				.thenThrow(new DataIntegrityViolationException(
						"ERROR: duplicate key value violates unique constraint \"uk_migration_definition_name_version\""));

		CreateMigrationDefinitionRequest request = new CreateMigrationDefinitionRequest(
				"uam-address-import",
				"1.0.0",
				null,
				"FILE",
				"UAM",
				null,
				null,
				null,
				null,
				null);

		assertThatThrownBy(() -> service.create(request))
				.isInstanceOf(DuplicateMigrationDefinitionException.class);
	}

	@Test
	void getByIdThrowsWhenMissing() {
		UUID id = UUID.randomUUID();
		when(migrationDefinitionRepository.findById(id)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.getById(id))
				.isInstanceOf(MigrationDefinitionNotFoundException.class);
	}

	@Test
	void listCapsPageSizeAtMaximum() {
		when(migrationDefinitionRepository.findAll(any(Pageable.class)))
				.thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

		PagedResponse<MigrationDefinitionResponse> page = service.list(0, 500);

		assertThat(page.size()).isEqualTo(100);
		ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
		verify(migrationDefinitionRepository).findAll(captor.capture());
		assertThat(captor.getValue().getPageSize()).isEqualTo(100);
	}

	@Test
	void activateTransitionsDraftToActive() {
		UUID id = UUID.randomUUID();
		MigrationDefinition definition = readyDraft(id);
		Instant previousUpdatedAt = definition.getUpdatedAt();
		when(migrationDefinitionRepository.findByIdForUpdate(id)).thenReturn(Optional.of(definition));
		stubReadyGraph(definition);

		MigrationDefinitionResponse response = service.activate(id);

		assertThat(response.status()).isEqualTo(MigrationConfigStatus.ACTIVE);
		assertThat(definition.getStatus()).isEqualTo(MigrationConfigStatus.ACTIVE);
		assertThat(definition.getUpdatedBy()).isEqualTo("system");
		assertThat(definition.getUpdatedAt()).isAfter(previousUpdatedAt);
		verify(migrationDefinitionRepository).findByIdForUpdate(id);
		verify(migrationDefinitionRepository, never()).findById(id);
	}

	@Test
	void activateAcceptsFileSystemCsvSourceConfiguration() {
		UUID id = UUID.randomUUID();
		MigrationDefinition definition = readyDraft(id);
		when(migrationDefinitionRepository.findByIdForUpdate(id)).thenReturn(Optional.of(definition));
		stubReadyGraph(definition, "FILE_SYSTEM", fileSystemCsvConfiguration("demo/addresses.csv"));

		assertThat(service.activate(id).status()).isEqualTo(MigrationConfigStatus.ACTIVE);
		assertThat(definition.getStatus()).isEqualTo(MigrationConfigStatus.ACTIVE);
		verify(artifactStorage, never()).exists(any(), any());
		verify(artifactStorage, never()).open(any(), any());
		verify(artifactStorage, never()).metadata(any(), any());
	}

	@Test
	void activateRejectsUnsupportedFileFormat() {
		UUID id = UUID.randomUUID();
		MigrationDefinition definition = readyDraft(id);
		when(migrationDefinitionRepository.findByIdForUpdate(id)).thenReturn(Optional.of(definition));
		stubReadyGraph(definition, "FILE_SYSTEM", Map.of(
				"provider", "FILE_SYSTEM",
				"objectKey", "demo/addresses.csv",
				"fileFormat", "JSON"));

		assertThatThrownBy(() -> service.activate(id))
				.isInstanceOf(UnsupportedFileFormatException.class);
		assertThat(definition.getStatus()).isEqualTo(MigrationConfigStatus.DRAFT);
	}

	@Test
	void activateIsIdempotentWhenAlreadyActive() {
		UUID id = UUID.randomUUID();
		MigrationDefinition definition = readyDraft(id);
		definition.setStatus(MigrationConfigStatus.ACTIVE);
		Instant previousUpdatedAt = definition.getUpdatedAt();
		when(migrationDefinitionRepository.findByIdForUpdate(id)).thenReturn(Optional.of(definition));

		MigrationDefinitionResponse response = service.activate(id);

		assertThat(response.status()).isEqualTo(MigrationConfigStatus.ACTIVE);
		assertThat(definition.getUpdatedAt()).isEqualTo(previousUpdatedAt);
		verify(migrationMappingRuleRepository, never()).findByMapping_IdAndEnabledTrueOrderBySequenceNoAsc(any());
		verify(migrationSourceRepository, never()).findByMigrationDefinition_IdAndEnabledTrueOrderByCreatedAtAsc(any());
	}

	@Test
	void activateThrowsWhenDefinitionMissing() {
		UUID id = UUID.randomUUID();
		when(migrationDefinitionRepository.findByIdForUpdate(id)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.activate(id))
				.isInstanceOf(MigrationDefinitionNotFoundException.class);
	}

	@Test
	void activateRejectsIncompleteDefinitionWithoutMapping() {
		UUID id = UUID.randomUUID();
		MigrationDefinition definition = readyDraft(id);
		definition.setMapping(null);
		when(migrationDefinitionRepository.findByIdForUpdate(id)).thenReturn(Optional.of(definition));

		assertThatThrownBy(() -> service.activate(id))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Migration mapping is not configured");
		assertThat(definition.getStatus()).isEqualTo(MigrationConfigStatus.DRAFT);
	}

	@Test
	void activateRejectsInvalidBatchSize() {
		UUID id = UUID.randomUUID();
		MigrationDefinition definition = readyDraft(id);
		definition.setBatchSize(0);
		when(migrationDefinitionRepository.findByIdForUpdate(id)).thenReturn(Optional.of(definition));

		assertThatThrownBy(() -> service.activate(id))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("batchSize must be greater than 0");
	}

	@Test
	void activateRejectsMissingEnabledRules() {
		UUID id = UUID.randomUUID();
		MigrationDefinition definition = readyDraft(id);
		when(migrationDefinitionRepository.findByIdForUpdate(id)).thenReturn(Optional.of(definition));
		when(migrationMappingRuleRepository.findByMapping_IdAndEnabledTrueOrderBySequenceNoAsc(definition.getMapping().getId()))
				.thenReturn(List.of());

		assertThatThrownBy(() -> service.activate(id))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("At least one enabled mapping rule is required");
		assertThat(definition.getStatus()).isEqualTo(MigrationConfigStatus.DRAFT);
	}

	@Test
	void activateRejectsMissingEnabledSource() {
		UUID id = UUID.randomUUID();
		MigrationDefinition definition = readyDraft(id);
		when(migrationDefinitionRepository.findByIdForUpdate(id)).thenReturn(Optional.of(definition));
		when(migrationMappingRuleRepository.findByMapping_IdAndEnabledTrueOrderBySequenceNoAsc(definition.getMapping().getId()))
				.thenReturn(List.of(new MigrationMappingRule()));
		when(migrationSourceRepository.findByMigrationDefinition_IdAndEnabledTrueOrderByCreatedAtAsc(id))
				.thenReturn(List.of());

		assertThatThrownBy(() -> service.activate(id))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("No enabled migration source is configured");
	}

	@Test
	void activateRejectsInvalidSourceConfiguration() {
		UUID id = UUID.randomUUID();
		MigrationDefinition definition = readyDraft(id);
		MigrationSource source = new MigrationSource();
		source.setId(UUID.randomUUID());
		source.setSourceType("FILE_SYSTEM");
		source.setConfiguration(Map.of());
		when(migrationDefinitionRepository.findByIdForUpdate(id)).thenReturn(Optional.of(definition));
		when(migrationMappingRuleRepository.findByMapping_IdAndEnabledTrueOrderBySequenceNoAsc(definition.getMapping().getId()))
				.thenReturn(List.of(new MigrationMappingRule()));
		when(migrationSourceRepository.findByMigrationDefinition_IdAndEnabledTrueOrderByCreatedAtAsc(id))
				.thenReturn(List.of(source));

		assertThatThrownBy(() -> service.activate(id))
				.isInstanceOf(InvalidSourceConfigurationException.class);
		assertThat(definition.getStatus()).isEqualTo(MigrationConfigStatus.DRAFT);
	}

	@Test
	void activateRejectsEntityRelationshipRuleWithoutEntity() {
		UUID id = UUID.randomUUID();
		MigrationDefinition definition = readyDraft(id);
		definition.getMapping().setConfiguration(singleEntityModel("Invoice"));
		MigrationMappingRule rule = new MigrationMappingRule();
		rule.setEnabled(true);
		when(migrationDefinitionRepository.findByIdForUpdate(id)).thenReturn(Optional.of(definition));
		when(migrationMappingRuleRepository.findByMapping_IdAndEnabledTrueOrderBySequenceNoAsc(definition.getMapping().getId()))
				.thenReturn(List.of(rule));
		when(migrationMappingRuleRepository.findByMapping_IdOrderBySequenceNoAsc(definition.getMapping().getId()))
				.thenReturn(List.of(rule));

		assertThatThrownBy(() -> service.activate(id))
				.isInstanceOf(InvalidEntityRelationshipConfigurationException.class)
				.hasMessage("Mapping rule entity is required for an ENTITY_RELATIONSHIP mapping.");
		assertThat(definition.getStatus()).isEqualTo(MigrationConfigStatus.DRAFT);
	}

	@Test
	void activateRejectsRuleEntityThatIsNotConfigured() {
		UUID id = UUID.randomUUID();
		MigrationDefinition definition = readyDraft(id);
		definition.getMapping().setConfiguration(singleEntityModel("Invoice"));
		MigrationMappingRule rule = new MigrationMappingRule();
		rule.setEnabled(true);
		rule.setEntityName("Address");
		when(migrationDefinitionRepository.findByIdForUpdate(id)).thenReturn(Optional.of(definition));
		when(migrationMappingRuleRepository.findByMapping_IdAndEnabledTrueOrderBySequenceNoAsc(definition.getMapping().getId()))
				.thenReturn(List.of(rule));
		when(migrationMappingRuleRepository.findByMapping_IdOrderBySequenceNoAsc(definition.getMapping().getId()))
				.thenReturn(List.of(rule));

		assertThatThrownBy(() -> service.activate(id))
				.isInstanceOf(InvalidEntityRelationshipConfigurationException.class)
				.hasMessage("Mapping rule entity 'Address' is not defined in the migration mapping configuration.");
		assertThat(definition.getStatus()).isEqualTo(MigrationConfigStatus.DRAFT);
	}

	@Test
	void activateAcceptsEntityScopedRule() {
		UUID id = UUID.randomUUID();
		MigrationDefinition definition = readyDraft(id);
		definition.getMapping().setConfiguration(singleEntityModel("Invoice"));
		MigrationMappingRule rule = new MigrationMappingRule();
		rule.setEnabled(true);
		rule.setEntityName("Invoice");
		when(migrationDefinitionRepository.findByIdForUpdate(id)).thenReturn(Optional.of(definition));
		when(migrationMappingRuleRepository.findByMapping_IdAndEnabledTrueOrderBySequenceNoAsc(definition.getMapping().getId()))
				.thenReturn(List.of(rule));
		when(migrationMappingRuleRepository.findByMapping_IdOrderBySequenceNoAsc(definition.getMapping().getId()))
				.thenReturn(List.of(rule));
		MigrationSource source = new MigrationSource();
		source.setId(UUID.randomUUID());
		source.setSourceType("FILE");
		source.setConfiguration(new HashMap<>(fileSystemCsvConfiguration("demo/invoices.csv")));
		when(migrationSourceRepository.findByMigrationDefinition_IdAndEnabledTrueOrderByCreatedAtAsc(definition.getId()))
				.thenReturn(List.of(source));

		assertThat(service.activate(id).status()).isEqualTo(MigrationConfigStatus.ACTIVE);
	}

	@Test
	void activateAcceptsMultiFileLayoutWithoutUsingTheSingleFileConnector() {
		UUID id = UUID.randomUUID();
		MigrationDefinition definition = readyDraft(id);
		definition.getMapping().setConfiguration(singleEntityModel("Invoice"));
		MigrationMappingRule rule = new MigrationMappingRule();
		rule.setEnabled(true);
		rule.setEntityName("Invoice");
		when(migrationDefinitionRepository.findByIdForUpdate(id)).thenReturn(Optional.of(definition));
		when(migrationMappingRuleRepository.findByMapping_IdAndEnabledTrueOrderBySequenceNoAsc(definition.getMapping().getId()))
				.thenReturn(List.of(rule));
		when(migrationMappingRuleRepository.findByMapping_IdOrderBySequenceNoAsc(definition.getMapping().getId()))
				.thenReturn(List.of(rule));
		MigrationSource source = new MigrationSource();
		source.setId(UUID.randomUUID());
		source.setSourceType("FILE");
		Map<String, Object> files = new LinkedHashMap<>();
		files.put("Invoice", Map.of("file", "invoice.csv"));
		Map<String, Object> configuration = new LinkedHashMap<>();
		configuration.put("layoutType", "MULTI_FILE");
		configuration.put("files", files);
		source.setConfiguration(configuration);
		when(migrationSourceRepository.findByMigrationDefinition_IdAndEnabledTrueOrderByCreatedAtAsc(definition.getId()))
				.thenReturn(List.of(source));

		assertThat(service.activate(id).status()).isEqualTo(MigrationConfigStatus.ACTIVE);
		verify(artifactStorage, never()).exists(any(), any());
	}

	@Test
	void activateRejectsMultiFileEntityThatIsNotConfigured() {
		UUID id = UUID.randomUUID();
		MigrationDefinition definition = readyDraft(id);
		definition.getMapping().setConfiguration(singleEntityModel("Invoice"));
		MigrationMappingRule rule = new MigrationMappingRule();
		rule.setEnabled(true);
		rule.setEntityName("Invoice");
		when(migrationDefinitionRepository.findByIdForUpdate(id)).thenReturn(Optional.of(definition));
		when(migrationMappingRuleRepository.findByMapping_IdAndEnabledTrueOrderBySequenceNoAsc(definition.getMapping().getId()))
				.thenReturn(List.of(rule));
		when(migrationMappingRuleRepository.findByMapping_IdOrderBySequenceNoAsc(definition.getMapping().getId()))
				.thenReturn(List.of(rule));
		MigrationSource source = new MigrationSource();
		source.setSourceType("FILE");
		source.setConfiguration(Map.of(
				"layoutType", "MULTI_FILE",
				"files", Map.of("Address", Map.of("file", "address.csv"))));
		when(migrationSourceRepository.findByMigrationDefinition_IdAndEnabledTrueOrderByCreatedAtAsc(definition.getId()))
				.thenReturn(List.of(source));

		assertThatThrownBy(() -> service.activate(id))
				.isInstanceOf(InvalidSourceConfigurationException.class)
				.hasMessage("Source file entity 'Address' is not defined in the migration mapping configuration.");
		assertThat(definition.getStatus()).isEqualTo(MigrationConfigStatus.DRAFT);
	}

	@Test
	void activateRejectsInvalidEntityRelationshipConfiguration() {
		UUID id = UUID.randomUUID();
		MigrationDefinition definition = readyDraft(id);
		definition.getMapping().setConfiguration(Map.of(
				"modelType", "ENTITY_RELATIONSHIP",
				"rootEntity", "Missing",
				"entities", List.of(Map.of(
						"name", "Invoice",
						"targetEntity", "invoice",
						"sourceKey", "invoice_id",
						"targetKey", "id")),
				"relationships", List.of()));
		when(migrationDefinitionRepository.findByIdForUpdate(id)).thenReturn(Optional.of(definition));

		assertThatThrownBy(() -> service.activate(id))
				.isInstanceOf(InvalidEntityRelationshipConfigurationException.class)
				.hasMessage("rootEntity 'Missing' does not match a configured entity");
		assertThat(definition.getStatus()).isEqualTo(MigrationConfigStatus.DRAFT);
	}

	@Test
	void activateRejectsInvalidPromotionConfiguration() {
		UUID id = UUID.randomUUID();
		MigrationDefinition definition = readyDraft(id);
		definition.setConfiguration(Map.of("promotion", "not-an-object"));
		when(migrationDefinitionRepository.findByIdForUpdate(id)).thenReturn(Optional.of(definition));

		assertThatThrownBy(() -> service.activate(id))
				.isInstanceOf(InvalidPromotionConfigurationException.class)
				.hasMessage("Promotion configuration must be an object");
		assertThat(definition.getStatus()).isEqualTo(MigrationConfigStatus.DRAFT);
	}

	@Test
	void activateLeavesOnlyTheRequestedVersionActive() {
		UUID previousId = UUID.randomUUID();
		UUID requestedId = UUID.randomUUID();
		MigrationDefinition previous = readyDraft(previousId);
		previous.setName("migrate_sample");
		previous.setVersion("1.0");
		previous.setStatus(MigrationConfigStatus.ACTIVE);
		MigrationDefinition requested = readyDraft(requestedId);
		requested.setName("migrate_sample");
		requested.setVersion("1.1");
		stubReadyGraph(requested);
		when(migrationDefinitionRepository.findByIdForUpdate(requestedId)).thenReturn(Optional.of(requested));
		when(migrationDefinitionRepository.findOtherActiveByNameForUpdate(
				"migrate_sample", MigrationConfigStatus.ACTIVE, requestedId))
				.thenReturn(List.of(previous));

		MigrationDefinitionResponse response = service.activate(requestedId);

		assertThat(response.status()).isEqualTo(MigrationConfigStatus.ACTIVE);
		assertThat(requested.getStatus()).isEqualTo(MigrationConfigStatus.ACTIVE);
		assertThat(previous.getStatus()).isEqualTo(MigrationConfigStatus.INACTIVE);
	}

	@Test
	void activateDoesNotChangeDefinitionsWithADifferentName() {
		UUID requestedId = UUID.randomUUID();
		MigrationDefinition requested = readyDraft(requestedId);
		requested.setName("migrate_sample");
		requested.setVersion("1.1");
		MigrationDefinition otherName = readyDraft(UUID.randomUUID());
		otherName.setName("migrate_other");
		otherName.setVersion("1.0");
		otherName.setStatus(MigrationConfigStatus.ACTIVE);
		stubReadyGraph(requested);
		when(migrationDefinitionRepository.findByIdForUpdate(requestedId)).thenReturn(Optional.of(requested));
		when(migrationDefinitionRepository.findOtherActiveByNameForUpdate(
				"migrate_sample", MigrationConfigStatus.ACTIVE, requestedId))
				.thenReturn(List.of());

		service.activate(requestedId);

		assertThat(requested.getStatus()).isEqualTo(MigrationConfigStatus.ACTIVE);
		assertThat(otherName.getStatus()).isEqualTo(MigrationConfigStatus.ACTIVE);
		verify(migrationDefinitionRepository).findOtherActiveByNameForUpdate(
				"migrate_sample", MigrationConfigStatus.ACTIVE, requestedId);
	}

	@Test
	void activateAlreadyActiveVersionIsIdempotent() {
		UUID activeId = UUID.randomUUID();
		UUID siblingId = UUID.randomUUID();
		MigrationDefinition active = readyDraft(activeId);
		active.setName("migrate_sample");
		active.setVersion("1.1");
		active.setStatus(MigrationConfigStatus.ACTIVE);
		Instant previousUpdatedAt = active.getUpdatedAt();
		MigrationDefinition sibling = readyDraft(siblingId);
		sibling.setName("migrate_sample");
		sibling.setVersion("1.0");
		sibling.setStatus(MigrationConfigStatus.ACTIVE);
		when(migrationDefinitionRepository.findByIdForUpdate(activeId)).thenReturn(Optional.of(active));
		when(migrationDefinitionRepository.findOtherActiveByNameForUpdate(
				"migrate_sample", MigrationConfigStatus.ACTIVE, activeId))
				.thenReturn(List.of(sibling));

		MigrationDefinitionResponse response = service.activate(activeId);

		assertThat(response.status()).isEqualTo(MigrationConfigStatus.ACTIVE);
		assertThat(active.getStatus()).isEqualTo(MigrationConfigStatus.ACTIVE);
		assertThat(active.getUpdatedAt()).isEqualTo(previousUpdatedAt);
		assertThat(sibling.getStatus()).isEqualTo(MigrationConfigStatus.INACTIVE);
		verify(migrationMappingRuleRepository, never()).findByMapping_IdAndEnabledTrueOrderBySequenceNoAsc(any());
		verify(migrationSourceRepository, never()).findByMigrationDefinition_IdAndEnabledTrueOrderByCreatedAtAsc(any());
	}

	@Test
	void createAcceptsBulkPostgreSqlConfigurationWithStagingTable() {
		when(migrationDefinitionRepository.existsByNameAndVersion("bulk-import", "1.0.0")).thenReturn(false);
		when(migrationDefinitionRepository.save(any(MigrationDefinition.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		Map<String, Object> configuration = new LinkedHashMap<>();
		configuration.put("target", Map.of(
				"mode", "UAM",
				"schema", "app",
				"stagingSchema", "migration_staging",
				"stagingTable", "stage_table"));
		configuration.put("promotion", Map.of("enabled", true));

		MigrationDefinitionResponse response = service.create(new CreateMigrationDefinitionRequest(
				"bulk-import",
				"1.0.0",
				null,
				"FILE",
				"POSTGRESQL",
				null,
				null,
				null,
				LoadMode.BULK,
				configuration));

		assertThat(response.status()).isEqualTo(MigrationConfigStatus.DRAFT);
		assertThat(response.configuration()).containsKey("target");
	}

	@Test
	void createRejectsBulkPostgreSqlConfigurationWithoutStagingTable() {
		when(migrationDefinitionRepository.existsByNameAndVersion("bulk-import", "1.0.0")).thenReturn(false);

		assertThatThrownBy(() -> service.create(new CreateMigrationDefinitionRequest(
				"bulk-import",
				"1.0.0",
				null,
				"FILE",
				"POSTGRESQL",
				null,
				null,
				null,
				LoadMode.BULK,
				Map.of("target", Map.of("stagingSchema", "migration_staging")))))
				.isInstanceOfSatisfying(RequestValidationException.class, failure -> {
					assertThat(failure.errors()).singleElement().satisfies(error -> {
						assertThat(error.field()).isEqualTo("configuration.target.stagingTable");
						assertThat(error.message()).isEqualTo("stagingTable is required for POSTGRESQL target");
					});
				});
		verify(migrationDefinitionRepository, never()).save(any());
	}

	@Test
	void activateRejectsInactiveStatus() {
		UUID id = UUID.randomUUID();
		MigrationDefinition definition = readyDraft(id);
		definition.setStatus(MigrationConfigStatus.INACTIVE);
		when(migrationDefinitionRepository.findByIdForUpdate(id)).thenReturn(Optional.of(definition));

		assertThatThrownBy(() -> service.activate(id))
				.isInstanceOf(InvalidStateTransitionException.class)
				.hasMessageContaining("INACTIVE");
	}

	private void stubReadyGraph(MigrationDefinition definition) {
		stubReadyGraph(definition, "FILE", fileSystemCsvConfiguration("demo/addresses.csv"));
	}

	private void stubReadyGraph(MigrationDefinition definition, String sourceType, Map<String, Object> configuration) {
		when(migrationMappingRuleRepository.findByMapping_IdAndEnabledTrueOrderBySequenceNoAsc(definition.getMapping().getId()))
				.thenReturn(List.of(new MigrationMappingRule()));
		MigrationSource source = new MigrationSource();
		source.setId(UUID.randomUUID());
		source.setSourceType(sourceType);
		source.setConfiguration(new HashMap<>(configuration));
		when(migrationSourceRepository.findByMigrationDefinition_IdAndEnabledTrueOrderByCreatedAtAsc(definition.getId()))
				.thenReturn(List.of(source));
	}

	private static Map<String, Object> singleEntityModel(String name) {
		Map<String, Object> entity = new LinkedHashMap<>();
		entity.put("name", name);
		entity.put("targetEntity", "invoice");
		entity.put("sourceKey", "invoice_id");
		entity.put("targetKey", "id");
		Map<String, Object> configuration = new LinkedHashMap<>();
		configuration.put("modelType", "ENTITY_RELATIONSHIP");
		configuration.put("rootEntity", name);
		configuration.put("entities", List.of(entity));
		configuration.put("relationships", List.of());
		return configuration;
	}

	private static Map<String, Object> fileSystemCsvConfiguration(String objectKey) {
		Map<String, Object> configuration = new HashMap<>();
		configuration.put("provider", "FILE_SYSTEM");
		configuration.put("objectKey", objectKey);
		configuration.put("fileFormat", "CSV");
		configuration.put("hasHeader", true);
		return configuration;
	}

	private static MigrationDefinition readyDraft(UUID id) {
		MigrationMapping mapping = new MigrationMapping();
		mapping.setId(UUID.randomUUID());
		MigrationDefinition definition = new MigrationDefinition();
		definition.setId(id);
		definition.setName("activate-def");
		definition.setVersion("1.0.0");
		definition.setSourceType("FILE");
		definition.setTargetType("POSTGRESQL");
		definition.setMapping(mapping);
		definition.setBatchSize(10_000);
		definition.setLoadMode(LoadMode.BULK);
		definition.setStatus(MigrationConfigStatus.DRAFT);
		Map<String, Object> target = new HashMap<>();
		target.put("stagingSchema", "migration_staging");
		target.put("stagingTable", "stage_table");
		Map<String, Object> configuration = new HashMap<>();
		configuration.put("target", target);
		definition.setConfiguration(configuration);
		definition.setCreatedAt(Instant.parse("2026-09-21T00:00:00Z"));
		definition.setUpdatedAt(Instant.parse("2026-09-21T00:00:00Z"));
		definition.setCreatedBy("system");
		definition.setUpdatedBy("system");
		return definition;
	}
}
