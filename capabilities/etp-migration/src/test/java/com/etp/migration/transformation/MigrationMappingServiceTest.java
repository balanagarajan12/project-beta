package com.etp.migration.transformation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.etp.migration.entity.MigrationConfigStatus;
import com.etp.migration.entity.MigrationMapping;
import com.etp.migration.entity.TransformationType;
import com.etp.migration.migration.DuplicateMigrationMappingException;
import com.etp.migration.migration.DuplicateMappingRuleException;
import com.etp.migration.migration.MappingNotFoundException;
import com.etp.migration.repository.MigrationMappingRepository;
import com.etp.migration.repository.MigrationMappingRuleRepository;
import com.etp.migration.transformation.dto.CreateMappingRuleRequest;
import com.etp.migration.transformation.dto.CreateMigrationMappingRequest;
import com.etp.migration.transformation.dto.MigrationMappingResponse;
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
				1, "addr1", "streetName", TransformationType.DIRECT, null, null, null, null, null);

		assertThatThrownBy(() -> service.createRule(mappingId, request))
				.isInstanceOf(MappingNotFoundException.class);
	}

	@Test
	void createRuleRejectsDuplicateSequence() {
		UUID mappingId = UUID.randomUUID();
		MigrationMapping mapping = new MigrationMapping();
		mapping.setId(mappingId);
		when(migrationMappingRepository.findById(mappingId)).thenReturn(Optional.of(mapping));
		when(migrationMappingRuleRepository.existsByMapping_IdAndSequenceNo(mappingId, 1)).thenReturn(true);

		CreateMappingRuleRequest request = new CreateMappingRuleRequest(
				1, "addr1", "streetName", TransformationType.DIRECT, null, null, null, null, null);

		assertThatThrownBy(() -> service.createRule(mappingId, request))
				.isInstanceOf(DuplicateMappingRuleException.class);
	}
}
