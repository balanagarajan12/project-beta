package com.etp.migration.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.etp.migration.entity.LoadMode;
import com.etp.migration.entity.MigrationConfigStatus;
import com.etp.migration.entity.TransformationType;
import com.etp.migration.migration.dto.CompositeDefinitionRequest;
import com.etp.migration.migration.dto.CompositeMigrationConfigurationRequest;
import com.etp.migration.migration.dto.CompositeMigrationConfigurationResponse;
import com.etp.migration.migration.dto.CreateMigrationDefinitionRequest;
import com.etp.migration.migration.dto.CreateMigrationSourceRequest;
import com.etp.migration.migration.dto.MigrationDefinitionResponse;
import com.etp.migration.migration.dto.MigrationSourceResponse;
import com.etp.migration.transformation.MigrationMappingService;
import com.etp.migration.transformation.dto.CreateMappingRuleRequest;
import com.etp.migration.transformation.dto.CreateMigrationMappingRequest;
import com.etp.migration.transformation.dto.MigrationMappingResponse;
import com.etp.migration.transformation.dto.MigrationMappingRuleResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CompositeMigrationConfigurationServiceTest {

	private static final UUID MAPPING_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID DEFINITION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID SOURCE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

	@Mock
	private MigrationMappingService migrationMappingService;
	@Mock
	private MigrationDefinitionService migrationDefinitionService;
	@Mock
	private MigrationSourceService migrationSourceService;

	private CompositeMigrationConfigurationService service;

	@BeforeEach
	void setUp() {
		service = new CompositeMigrationConfigurationService(
				migrationMappingService, migrationDefinitionService, migrationSourceService);
	}

	@Test
	void createsInMappingRulesDefinitionSourceOrderAndPropagatesIds() {
		when(migrationMappingService.create(any())).thenReturn(mappingResponse());
		when(migrationMappingService.createRule(eq(MAPPING_ID), any()))
				.thenReturn(ruleResponse(1L, 1))
				.thenReturn(ruleResponse(2L, 2));
		when(migrationDefinitionService.create(any())).thenReturn(definitionResponse());
		when(migrationSourceService.create(eq(DEFINITION_ID), any())).thenReturn(sourceResponse());

		CompositeMigrationConfigurationResponse response = service.create(request());

		InOrder order = inOrder(migrationMappingService, migrationDefinitionService, migrationSourceService);
		order.verify(migrationMappingService).create(any());
		order.verify(migrationMappingService, org.mockito.Mockito.times(2)).createRule(eq(MAPPING_ID), any());
		ArgumentCaptor<CreateMigrationDefinitionRequest> definitionCaptor =
				ArgumentCaptor.forClass(CreateMigrationDefinitionRequest.class);
		order.verify(migrationDefinitionService).create(definitionCaptor.capture());
		order.verify(migrationSourceService).create(eq(DEFINITION_ID), any());
		assertThat(definitionCaptor.getValue().mappingId()).isEqualTo(MAPPING_ID);
		assertThat(response.definition().mappingId()).isEqualTo(MAPPING_ID);
		assertThat(response.sources().getFirst().migrationDefinitionId()).isEqualTo(DEFINITION_ID);
		assertThat(response.rules()).extracting(MigrationMappingRuleResponse::sequenceNo).containsExactly(1, 2);
		assertThat(response.definition().status()).isEqualTo(MigrationConfigStatus.DRAFT);
		assertThat(response.mapping().status()).isEqualTo(MigrationConfigStatus.DRAFT);
		assertThat(response.definition().loadMode()).isEqualTo(LoadMode.BULK);
		assertThat(response.definition().batchSize()).isEqualTo(10_000);
		assertThat(response.sources().getFirst().enabled()).isTrue();
	}

	@Test
	void singleEntityWithoutTargetEntityIsRejectedBeforePersistence() {
		CompositeMigrationConfigurationRequest request = new CompositeMigrationConfigurationRequest(
				new CompositeDefinitionRequest(
						"addr-import", "1.0.0", null, "FILE", "POSTGRESQL", " ", null, null, Map.of()),
				new CreateMigrationMappingRequest("addr-map", "1.0.0", "FILE", "POSTGRESQL", null, null),
				List.of(new CreateMappingRuleRequest(
						1, null, "external_id", "external_id", TransformationType.DIRECT, null, null, null, true, true)),
				new CreateMigrationSourceRequest("csv", "FILE", Map.of("provider", "FILE_SYSTEM"), null));

		assertThatThrownBy(() -> service.create(request))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("definition.targetEntity is required for a single-entity migration");
		verify(migrationMappingService, never()).create(any());
	}

	@Test
	void duplicateDefinitionStopsBeforeSourceCreate() {
		when(migrationMappingService.create(any())).thenReturn(mappingResponse());
		when(migrationMappingService.createRule(eq(MAPPING_ID), any())).thenReturn(ruleResponse(1L, 1));
		when(migrationDefinitionService.create(any()))
				.thenThrow(new DuplicateMigrationDefinitionException("addr", "1.0.0"));

		assertThatThrownBy(() -> service.create(request()))
				.isInstanceOf(DuplicateMigrationDefinitionException.class);
		verify(migrationSourceService, never()).create(any(), any());
	}

	@Test
	void duplicateMappingStopsBeforeRulesDefinitionAndSource() {
		when(migrationMappingService.create(any()))
				.thenThrow(new DuplicateMigrationMappingException("map", "1.0.0"));

		assertThatThrownBy(() -> service.create(request()))
				.isInstanceOf(DuplicateMigrationMappingException.class);
		verify(migrationMappingService, never()).createRule(any(), any());
		verify(migrationDefinitionService, never()).create(any());
		verify(migrationSourceService, never()).create(any(), any());
	}

	@Test
	void duplicateRuleSequenceStopsBeforeDefinitionAndSource() {
		when(migrationMappingService.create(any())).thenReturn(mappingResponse());
		when(migrationMappingService.createRule(eq(MAPPING_ID), any()))
				.thenThrow(new DuplicateMappingRuleException(1));

		assertThatThrownBy(() -> service.create(request()))
				.isInstanceOf(DuplicateMappingRuleException.class);
		verify(migrationDefinitionService, never()).create(any());
		verify(migrationSourceService, never()).create(any(), any());
	}

	private CompositeMigrationConfigurationRequest request() {
		return new CompositeMigrationConfigurationRequest(
				new CompositeDefinitionRequest(
						"addr-import", "1.0.0", null, "FILE", "POSTGRESQL", "address", null, null, Map.of()),
				new CreateMigrationMappingRequest("addr-map", "1.0.0", "FILE", "POSTGRESQL", null, null),
				List.of(
						new CreateMappingRuleRequest(
								1, null, "external_id", "external_id", TransformationType.DIRECT, null, null, null, true, true),
						new CreateMappingRuleRequest(
								2, null, "city", "city", TransformationType.DIRECT, null, null, null, false, true)),
				new CreateMigrationSourceRequest("csv", "FILE", Map.of("provider", "FILE_SYSTEM"), null));
	}

	private static MigrationMappingResponse mappingResponse() {
		Instant now = Instant.parse("2026-09-21T00:00:00Z");
		return new MigrationMappingResponse(
				MAPPING_ID, "addr-map", "1.0.0", "FILE", "POSTGRESQL", null, Map.of(),
				MigrationConfigStatus.DRAFT, now, "system", now, "system");
	}

	private static MigrationMappingRuleResponse ruleResponse(long id, int sequence) {
		Instant now = Instant.parse("2026-09-21T00:00:00Z");
		return new MigrationMappingRuleResponse(
				id, MAPPING_ID, sequence, null, "f", "f", TransformationType.DIRECT, null, null, Map.of(),
				false, true, now, now);
	}

	private static MigrationDefinitionResponse definitionResponse() {
		Instant now = Instant.parse("2026-09-21T00:00:00Z");
		return new MigrationDefinitionResponse(
				DEFINITION_ID, "addr-import", "1.0.0", null, "FILE", "POSTGRESQL", "address",
				MAPPING_ID, 10_000, LoadMode.BULK, MigrationConfigStatus.DRAFT, Map.of(),
				now, "system", now, "system");
	}

	private static MigrationSourceResponse sourceResponse() {
		Instant now = Instant.parse("2026-09-21T00:00:00Z");
		return new MigrationSourceResponse(
				SOURCE_ID, DEFINITION_ID, "csv", "FILE", Map.of(), true, now, "system", now, "system");
	}
}
