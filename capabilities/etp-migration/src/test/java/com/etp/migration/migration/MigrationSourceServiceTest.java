package com.etp.migration.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.etp.migration.entity.MigrationDefinition;
import com.etp.migration.entity.MigrationSource;
import com.etp.migration.migration.dto.CreateMigrationSourceRequest;
import com.etp.migration.migration.dto.MigrationSourceResponse;
import com.etp.migration.repository.MigrationDefinitionRepository;
import com.etp.migration.repository.MigrationSourceRepository;
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
}
