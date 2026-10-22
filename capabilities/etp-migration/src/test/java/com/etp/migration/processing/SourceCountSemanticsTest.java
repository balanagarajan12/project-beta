package com.etp.migration.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.etp.migration.connector.source.InvalidSourceConfigurationException;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SourceCountSemanticsTest {

	@Test
	void entitySnapshotKeepsWorkItemsAndPhysicalRowsDistinct() {
		Map<String, Object> snapshot = SourceInspectionService.entitySnapshot(
				UUID.fromString("b681e62a-b41a-4168-bcbb-6be940548cfe"),
				3_250_000L,
				OptionalLong.of(500_000L));

		@SuppressWarnings("unchecked")
		Map<String, Object> source = (Map<String, Object>) snapshot.get("source");
		assertThat(source.get("recordCount")).isEqualTo(3_250_000L);
		assertThat(source.get("workItemCount")).isEqualTo(3_250_000L);
		assertThat(source.get("sourceRowCount")).isEqualTo(500_000L);
	}

	@Test
	void aDeclaredRowCountThatDisagreesWithTheFileIsRejected() {
		assertThatThrownBy(() -> SourceInspectionService.rejectDeclaredRowCount(
				Map.of("recordCount", 500),
				OptionalLong.of(4L)))
				.isInstanceOf(InvalidSourceConfigurationException.class)
				.hasMessageContaining("does not match the 4 data rows");
	}

	@Test
	void aMissingDeclaredRowCountDoesNotChangeTheWorkload() {
		SourceInspectionService.rejectDeclaredRowCount(Map.of("provider", "SFTP"), OptionalLong.of(4L));
		SourceInspectionService.rejectDeclaredRowCount(Map.of("recordCount", 4), OptionalLong.of(4L));
		SourceInspectionService.rejectDeclaredRowCount(Map.of("recordCount", 4), OptionalLong.empty());
	}
}
