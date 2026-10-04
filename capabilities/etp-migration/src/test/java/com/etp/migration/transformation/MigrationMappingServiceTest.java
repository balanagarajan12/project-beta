package com.etp.migration.transformation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.etp.migration.entity.MigrationConfigStatus;
import com.etp.migration.mapping.EntityRelationshipConfiguration;
import com.etp.migration.mapping.InvalidEntityRelationshipConfigurationException;
import com.etp.migration.entity.MigrationMapping;
import com.etp.migration.entity.MigrationMappingRule;
import com.etp.migration.entity.TransformationType;
import com.etp.migration.migration.DuplicateMigrationMappingException;
import com.etp.migration.migration.DuplicateMappingRuleException;
import com.etp.migration.migration.MappingNotFoundException;
import com.etp.migration.repository.MigrationMappingRepository;
import com.etp.migration.repository.MigrationMappingRuleRepository;
import com.etp.migration.transformation.dto.CreateMappingRuleRequest;
import com.etp.migration.transformation.dto.CreateMigrationMappingRequest;
import com.etp.migration.transformation.dto.MigrationMappingResponse;
import com.etp.migration.transformation.dto.MigrationMappingRuleResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MigrationMappingServiceTest {

	@Mock
	private MigrationMappingRepository migrationMappingRepository;

	@Mock
	private MigrationMappingRuleRepository migrationMappingRuleRepository;

	private MigrationMappingService service;

	@BeforeEach
	void setUp() {
		service = new MigrationMappingService(migrationMappingRepository, migrationMappingRuleRepository);
	}

	@Test
	void createAppliesDraftStatus() {
		when(migrationMappingRepository.existsByNameAndVersion("file-to-uam", "1.0.0")).thenReturn(false);
		when(migrationMappingRepository.save(any(MigrationMapping.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		CreateMigrationMappingRequest request = new CreateMigrationMappingRequest(
				"file-to-uam", "1.0.0", "FILE", "UAM", null, null);

		MigrationMappingResponse response = service.create(request);

		assertThat(response.status()).isEqualTo(MigrationConfigStatus.DRAFT);
		assertThat(response.configuration()).isEmpty();
	}

	@Test
	void createPersistsEntityRelationshipConfiguration() {
		when(migrationMappingRepository.existsByNameAndVersion("generic", "1.0.0")).thenReturn(false);
		when(migrationMappingRepository.save(any(MigrationMapping.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		Map<String, Object> configuration = entityRelationship("Invoice", "invoice", "invoice_id", "id");

		MigrationMappingResponse response = service.create(new CreateMigrationMappingRequest(
				"generic", "1.0.0", "FILE", "POSTGRESQL", null, configuration));

		assertThat(EntityRelationshipConfiguration.parse(response.configuration()).rootEntity()).isEqualTo("Invoice");
		assertThat(response.configuration()).containsEntry("modelType", "ENTITY_RELATIONSHIP");
	}

	@Test
	void createRejectsInvalidEntityRelationshipConfiguration() {
		when(migrationMappingRepository.existsByNameAndVersion("generic", "1.0.0")).thenReturn(false);
		Map<String, Object> configuration = new LinkedHashMap<>();
		configuration.put("modelType", "ENTITY_RELATIONSHIP");
		configuration.put("rootEntity", "Invoice");
		configuration.put("entities", List.of());

		assertThatThrownBy(() -> service.create(new CreateMigrationMappingRequest(
				"generic", "1.0.0", "FILE", "POSTGRESQL", null, configuration)))
				.isInstanceOf(InvalidEntityRelationshipConfigurationException.class)
				.hasMessage("entities are required");
		verify(migrationMappingRepository, never()).save(any());
	}

	@Test
	void createRejectsDuplicateNameAndVersion() {
		when(migrationMappingRepository.existsByNameAndVersion("file-to-uam", "1.0.0")).thenReturn(true);

		CreateMigrationMappingRequest request = new CreateMigrationMappingRequest(
				"file-to-uam", "1.0.0", "FILE", "UAM", null, null);

		assertThatThrownBy(() -> service.create(request))
				.isInstanceOf(DuplicateMigrationMappingException.class);
	}

	@Test
	void createRuleRejectsUnknownMapping() {
		UUID mappingId = UUID.randomUUID();
		when(migrationMappingRepository.findById(mappingId)).thenReturn(Optional.empty());

		CreateMappingRuleRequest request = new CreateMappingRuleRequest(
				1, null, "addr1", "streetName", TransformationType.DIRECT, null, null, null, null, null);

		assertThatThrownBy(() -> service.createRule(mappingId, request))
				.isInstanceOf(MappingNotFoundException.class);
	}

	@Test
	void createRulePersistsLegacyRuleWithoutEntity() {
		UUID mappingId = UUID.randomUUID();
		MigrationMapping mapping = new MigrationMapping();
		mapping.setId(mappingId);
		when(migrationMappingRepository.findById(mappingId)).thenReturn(Optional.of(mapping));
		when(migrationMappingRuleRepository.existsByMapping_IdAndSequenceNo(mappingId, 1)).thenReturn(false);
		when(migrationMappingRuleRepository.save(any(MigrationMappingRule.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		MigrationMappingRuleResponse response = service.createRule(mappingId, new CreateMappingRuleRequest(
				1, null, "customer_name", "name", TransformationType.TRIM, null, null, Map.of(), true, true));

		assertThat(response.entity()).isNull();
	}

	@Test
	void createRulePersistsConfiguredEntity() {
		UUID mappingId = UUID.randomUUID();
		MigrationMapping mapping = new MigrationMapping();
		mapping.setId(mappingId);
		mapping.setConfiguration(relationshipModel("Invoice", "invoice_id"));
		when(migrationMappingRepository.findById(mappingId)).thenReturn(Optional.of(mapping));
		when(migrationMappingRuleRepository.existsByMapping_IdAndSequenceNo(mappingId, 1)).thenReturn(false);
		when(migrationMappingRuleRepository.save(any(MigrationMappingRule.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		MigrationMappingRuleResponse response = service.createRule(mappingId, new CreateMappingRuleRequest(
				1, " Invoice ", "invoice_number", "number", TransformationType.TRIM, null, null, Map.of(), true, true));

		assertThat(response.entity()).isEqualTo("Invoice");
	}

	@Test
	void createRuleRejectsMissingEntityForEntityRelationshipMapping() {
		UUID mappingId = preparedRelationshipMapping();

		assertThatThrownBy(() -> service.createRule(mappingId, new CreateMappingRuleRequest(
				1, null, "invoice_number", "number", TransformationType.TRIM, null, null, Map.of(), true, true)))
				.isInstanceOf(InvalidEntityRelationshipConfigurationException.class)
				.hasMessage("Mapping rule entity is required for an ENTITY_RELATIONSHIP mapping.");
		verify(migrationMappingRuleRepository, never()).save(any());
	}

	@Test
	void createRuleRejectsUnknownEntity() {
		UUID mappingId = preparedRelationshipMapping();

		assertThatThrownBy(() -> service.createRule(mappingId, new CreateMappingRuleRequest(
				1, "Address", "invoice_number", "number", TransformationType.TRIM, null, null, Map.of(), true, true)))
				.isInstanceOf(InvalidEntityRelationshipConfigurationException.class)
				.hasMessage("Mapping rule entity 'Address' is not defined in the migration mapping configuration.");
		verify(migrationMappingRuleRepository, never()).save(any());
	}

	@Test
	void rulesForEntityUsesEnabledRulesOrderedByTheRepository() {
		UUID mappingId = UUID.randomUUID();
		when(migrationMappingRepository.existsById(mappingId)).thenReturn(true);
		MigrationMapping mapping = new MigrationMapping();
		mapping.setId(mappingId);
		MigrationMappingRule first = storedRule(mapping, 1, "Invoice", "number");
		when(migrationMappingRuleRepository.findByMapping_IdAndEntityNameAndEnabledTrueOrderBySequenceNoAsc(mappingId, "Invoice"))
				.thenReturn(List.of(first));

		assertThat(service.rulesForEntity(mappingId, " Invoice "))
				.extracting(MigrationMappingRuleResponse::entity, MigrationMappingRuleResponse::sequenceNo)
				.containsExactly(org.assertj.core.groups.Tuple.tuple("Invoice", 1));
	}

	@Test
	void createRuleRejectsDuplicateSequence() {
		UUID mappingId = UUID.randomUUID();
		MigrationMapping mapping = new MigrationMapping();
		mapping.setId(mappingId);
		when(migrationMappingRepository.findById(mappingId)).thenReturn(Optional.of(mapping));
		when(migrationMappingRuleRepository.existsByMapping_IdAndSequenceNo(mappingId, 1)).thenReturn(true);

		CreateMappingRuleRequest request = new CreateMappingRuleRequest(
				1, null, "addr1", "streetName", TransformationType.DIRECT, null, null, null, null, null);

		assertThatThrownBy(() -> service.createRule(mappingId, request))
				.isInstanceOf(DuplicateMappingRuleException.class);
	}

	private UUID preparedRelationshipMapping() {
		UUID mappingId = UUID.randomUUID();
		MigrationMapping mapping = new MigrationMapping();
		mapping.setId(mappingId);
		mapping.setConfiguration(relationshipModel("Invoice", "invoice_id"));
		when(migrationMappingRepository.findById(mappingId)).thenReturn(Optional.of(mapping));
		when(migrationMappingRuleRepository.existsByMapping_IdAndSequenceNo(mappingId, 1)).thenReturn(false);
		return mappingId;
	}

	private static MigrationMappingRule storedRule(
			MigrationMapping mapping,
			int sequence,
			String entity,
			String targetField) {
		MigrationMappingRule rule = new MigrationMappingRule();
		rule.setMapping(mapping);
		rule.setSequenceNo(sequence);
		rule.setEntityName(entity);
		rule.setSourceField(targetField);
		rule.setTargetField(targetField);
		rule.setTransformationType(TransformationType.DIRECT);
		rule.setEnabled(true);
		rule.setConfiguration(Map.of());
		return rule;
	}

	private static Map<String, Object> relationshipModel(String name, String sourceKey) {
		Map<String, Object> entity = new LinkedHashMap<>();
		entity.put("name", name);
		entity.put("targetEntity", name.toLowerCase());
		entity.put("sourceKey", sourceKey);
		entity.put("targetKey", "id");
		Map<String, Object> configuration = new LinkedHashMap<>();
		configuration.put("modelType", "ENTITY_RELATIONSHIP");
		configuration.put("rootEntity", name);
		configuration.put("entities", List.of(entity));
		configuration.put("relationships", List.of());
		return configuration;
	}

	private static Map<String, Object> entityRelationship(
			String name,
			String targetEntity,
			String sourceKey,
			String targetKey) {
		Map<String, Object> entity = new LinkedHashMap<>();
		entity.put("name", name);
		entity.put("targetEntity", targetEntity);
		entity.put("sourceKey", sourceKey);
		entity.put("targetKey", targetKey);
		Map<String, Object> configuration = new LinkedHashMap<>();
		configuration.put("modelType", "ENTITY_RELATIONSHIP");
		configuration.put("rootEntity", name);
		configuration.put("entities", List.of(entity));
		configuration.put("relationships", List.of());
		return configuration;
	}
}
