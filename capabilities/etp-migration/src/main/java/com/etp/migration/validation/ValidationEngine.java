package com.etp.migration.validation;

import com.etp.migration.entity.MigrationMappingRule;
import com.etp.migration.entity.MigrationStage;
import com.etp.migration.processing.MigrationErrorCodes;
import com.etp.migration.processing.ProcessingError;
import com.etp.migration.processing.TargetRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ValidationEngine {

	public List<ProcessingError> validate(TargetRecord target, List<MigrationMappingRule> rules) {
		List<ProcessingError> errors = new ArrayList<>();
		Map<String, Object> fields = target == null || target.fields() == null ? Map.of() : target.fields();
		for (MigrationMappingRule rule : rules) {
			if (rule == null || !rule.isEnabled() || !rule.isMandatory()) {
				continue;
			}
			String field = rule.getTargetField();
			Object value = fields.get(field);
			if (value == null || String.valueOf(value).isBlank()) {
				errors.add(new ProcessingError(
						MigrationStage.VALIDATE,
						MigrationErrorCodes.VALIDATION_ERROR,
						"VALIDATION_FAILED",
						field,
						"Mandatory target field is missing",
						Map.of("targetField", field),
						false));
			}
		}
		return errors;
	}
}
