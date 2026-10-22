package com.etp.migration.connector.target.uam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.etp.migration.connector.target.LoadableTargetRecord;
import com.etp.migration.connector.target.MigrationTargetContext;
import com.etp.migration.connector.target.postgresql.PostgreSqlStagedEntityReader;
import com.etp.migration.identity.GeneratedTargetIdentityService;
import com.etp.migration.mapping.EntityRelationshipConfiguration;
import com.etp.migration.processing.TargetRecord;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UamStagingPromotionTest {

	@Mock
	private PostgreSqlStagedEntityReader stagedEntityReader;
	@Mock
	private GeneratedTargetIdentityService identityService;
	@Mock
	private UamPostgreSqlTargetConnector connector;

	@Test
	void promotionResolvesAddressLocationThatWasStagedInAnotherBatch() {
		UUID runId = UUID.randomUUID();
		Map<String, Object> mapping = geographicMapping();
		Map<String, Object> definition = Map.of("target", Map.of("mode", "UAM", "schema", "uam"));
		when(stagedEntityReader.read(eq(runId), eq(2L), eq(definition))).thenReturn(List.of(addressOnly()));
		when(identityService.assign(any(), any(), any(), any(), any(), any(), any(), isNull()))
				.thenAnswer(invocation -> {
					String entityName = invocation.getArgument(1);
					if ("AddressLocation".equals(entityName)) {
						return Map.of("addr-1", "LOC-000000001");
					}
					if ("GeographicAddress".equals(entityName)) {
						return Map.of("addr-1", "ADDR-0000000001");
					}
					return Map.of();
				});
		UamStagingPromotion promotion = new UamStagingPromotion(
				stagedEntityReader, identityService, connector, null);

		promotion.promoteBatch(runId, 2L, definition, mapping);

		ArgumentCaptor<List<LoadableTargetRecord>> records = ArgumentCaptor.forClass(List.class);
		verify(connector).load(any(MigrationTargetContext.class), records.capture());
		List<UamCanonicalRows.InsertGroup> groups = UamCanonicalRows.plan(definition, records.getValue());
		assertThat(groups).singleElement().satisfies(group -> {
			assertThat(group.table()).isEqualTo("geographic_address");
			assertThat(group.parameters().get(0).get(group.columns().indexOf("location_id"))).isEqualTo("LOC-000000001");
			assertThat(group.parameters().get(0).get(group.columns().indexOf("id"))).isEqualTo("ADDR-0000000001");
		});
	}

	private static LoadableTargetRecord addressOnly() {
		Map<String, Object> link = new LinkedHashMap<>();
		link.put("parentEntity", "AddressLocation");
		link.put("parentSourceIdentity", "addr-1");
		link.put("parentTargetEntity", "location");
		link.put("parentTargetIdentity", null);
		link.put("targetForeignKey", Map.of("column", "location_id", "referencesColumn", "id"));
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("entity", "GeographicAddress");
		fields.put("sourceIdentity", "addr-1");
		fields.put("targetEntity", "geographic_address");
		fields.put("targetKey", "id");
		fields.put("attributes", Map.of("street_name", "Sample"));
		fields.put("relationships", List.of(link));
		return new LoadableTargetRecord(1L, new TargetRecord(fields), "address-addr-1");
	}

	private static Map<String, Object> geographicMapping() {
		return EntityRelationshipConfiguration.toConfiguration(EntityRelationshipConfiguration.parse(Map.of(
				"modelType", "ENTITY_RELATIONSHIP",
				"rootEntity", "AddressLocation",
				"entities", List.of(
						entity("AddressLocation", "location", "LOC-"),
						entity("GeographicAddress", "geographic_address", "ADDR-")),
				"relationships", List.of(Map.of(
						"parent", "AddressLocation",
						"child", "GeographicAddress",
						"cardinality", "ONE_TO_ONE",
						"targetForeignKey", Map.of("column", "location_id", "referencesColumn", "id"))))));
	}

	private static Map<String, Object> entity(String name, String table, String prefix) {
		Map<String, Object> entity = new LinkedHashMap<>();
		entity.put("name", name);
		entity.put("targetEntity", table);
		entity.put("sourceKey", "address_id");
		entity.put("targetKey", "id");
		entity.put("targetIdentity", Map.of(
				"strategy", "PREFIX_SEQUENCE",
				"prefix", prefix,
				"sequenceLength", 9));
		return entity;
	}
}
