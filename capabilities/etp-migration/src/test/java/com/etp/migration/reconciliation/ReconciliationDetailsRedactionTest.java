package com.etp.migration.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.etp.migration.entity.MigrationReconciliation;
import com.etp.migration.entity.ReconciliationStatus;
import com.etp.migration.entity.ReconciliationType;
import com.etp.migration.reconciliation.dto.ReconciliationItemResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReconciliationDetailsRedactionTest {

	@Test
	void exampleSourceIdentityIsRedactedWithoutChangingTheStoredDocument() {
		Map<String, Object> secretExample = new LinkedHashMap<>();
		secretExample.put("entity", "geographic_address");
		secretExample.put("sourceIdentity", "password=hunter2");
		secretExample.put("reason", "LOAD_FAILURE");
		secretExample.put("resolvedTargetIdentity", "unresolved");
		Map<String, Object> safeExample = new LinkedHashMap<>();
		safeExample.put("entity", "geographic_address");
		safeExample.put("sourceIdentity", "addr-41");
		safeExample.put("reason", "PROCESSING_FAILED");
		safeExample.put("fieldName", "city");
		List<Object> examples = new ArrayList<>();
		examples.add(secretExample);
		examples.add(safeExample);
		Map<String, Object> stored = new LinkedHashMap<>();
		stored.put("modelType", "ENTITY_RELATIONSHIP");
		stored.put("entityStatus", "FAILED");
		stored.put("sourceChecksum", "abc123");
		stored.put("examples", examples);
		stored.put("reason", "key (id)=(1) already exists");
		stored.put("failureCount", 2L);
		MigrationReconciliation row = new MigrationReconciliation();
		row.setReconciliationType(ReconciliationType.FULL);
		row.setStatus(ReconciliationStatus.FAILED);
		row.setDetails(stored);

		ReconciliationItemResponse response = ReconciliationService.toItem(row);

		assertThat(response.type()).isEqualTo(ReconciliationType.FULL);
		assertThat(response.status()).isEqualTo(ReconciliationStatus.FAILED);
		assertThat(response.details())
				.containsEntry("modelType", "ENTITY_RELATIONSHIP")
				.containsEntry("entityStatus", "FAILED")
				.containsEntry("sourceChecksum", "abc123")
				.containsEntry("failureCount", 2L)
				.containsEntry("reason", "Error details were redacted");
		assertThat(response.details().get("examples")).isEqualTo(List.of(
				Map.of(
						"entity", "geographic_address",
						"sourceIdentity", "redacted",
						"reason", "LOAD_FAILURE",
						"resolvedTargetIdentity", "unresolved"),
				Map.of(
						"entity", "geographic_address",
						"sourceIdentity", "addr-41",
						"reason", "PROCESSING_FAILED",
						"fieldName", "city")));
		assertThat(response.details().toString()).doesNotContain("hunter2", "key (id)");
		assertThat(secretExample).containsEntry("sourceIdentity", "password=hunter2");
		assertThat(stored).containsEntry("reason", "key (id)=(1) already exists");
		assertThat(examples).hasSize(2);
	}
}
