package com.etp.migration.processing;

import com.etp.migration.entity.MigrationMappingRule;
import com.etp.migration.entity.MigrationRecordStatus;
import com.etp.migration.entity.MigrationStage;
import com.etp.migration.transformation.MappingEngine;
import com.etp.migration.validation.ValidationEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class MigrationRecordProcessor {

	private final MappingEngine mappingEngine;
	private final ValidationEngine validationEngine;

	public MigrationRecordProcessor(MappingEngine mappingEngine, ValidationEngine validationEngine) {
		this.mappingEngine = mappingEngine;
		this.validationEngine = validationEngine;
	}

	public ProcessedRecord process(SourceRecord source, List<MigrationMappingRule> rules) {
		try {
			TargetRecord target = mappingEngine.map(source, rules);
			List<ProcessingError> validationErrors = validationEngine.validate(target, rules);
			if (validationErrors.isEmpty()) {
				return new ProcessedRecord(source.recordNumber(), target, MigrationRecordStatus.SUCCESS, List.of());
			}
			return new ProcessedRecord(
					source.recordNumber(),
					target,
					MigrationRecordStatus.VALIDATION_FAILED,
					validationErrors);
		} catch (TransformationException ex) {
			ProcessingError error = new ProcessingError(
					MigrationStage.TRANSFORM,
					ex.errorCode(),
					"TRANSFORMATION_FAILED",
					ex.fieldName(),
					ex.getMessage(),
					Map.of(),
					false);
			return new ProcessedRecord(
					source.recordNumber(),
					new TargetRecord(Map.of()),
					MigrationRecordStatus.REJECTED,
					List.of(error));
		}
	}
}
