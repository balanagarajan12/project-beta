package com.etp.migration.processing;

import static org.assertj.core.api.Assertions.assertThat;

import com.etp.migration.entity.MigrationMappingRule;
import com.etp.migration.entity.MigrationRecordStatus;
import com.etp.migration.entity.TransformationType;
import com.etp.migration.transformation.MappingEngine;
import com.etp.migration.transformation.TransformationEngine;
import com.etp.migration.validation.ValidationEngine;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MigrationRecordProcessorTest {

	private final MigrationRecordProcessor processor = new MigrationRecordProcessor(
			new MappingEngine(new TransformationEngine()),
			new ValidationEngine());

	@Test
	void oneTransformationFailureDoesNotThrow() {
		MigrationMappingRule lookup = new MigrationMappingRule();
		lookup.setEnabled(true);
		lookup.setTransformationType(TransformationType.LOOKUP);
		lookup.setSourceField("city");
		lookup.setTargetField("city");
		lookup.setConfiguration(Map.of());

		ProcessedRecord result = processor.process(new SourceRecord(2L, Map.of("city", "Salem")), List.of(lookup));
		assertThat(result.status()).isEqualTo(MigrationRecordStatus.REJECTED);
		assertThat(result.errors()).isNotEmpty();
	}
}
