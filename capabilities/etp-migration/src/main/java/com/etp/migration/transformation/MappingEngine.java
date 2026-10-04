package com.etp.migration.transformation;

import com.etp.migration.entity.MigrationMappingRule;
import com.etp.migration.processing.SourceRecord;
import com.etp.migration.processing.TargetRecord;
import com.etp.migration.processing.TransformationException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class MappingEngine {

	private final TransformationEngine transformationEngine;

	public MappingEngine(TransformationEngine transformationEngine) {
		this.transformationEngine = transformationEngine;
	}

	public List<MigrationMappingRule> rulesForEntity(List<MigrationMappingRule> rules, String entity) {
		if (entity == null || entity.isBlank()) {
			throw new IllegalArgumentException("entity is required");
		}
		String name = entity.trim();
		List<MigrationMappingRule> source = rules == null ? List.of() : rules;
		return source.stream()
				.filter(rule -> rule != null && rule.isEnabled() && name.equals(rule.getEntityName()))
				.sorted(Comparator.comparing(MigrationMappingRule::getSequenceNo, Comparator.nullsLast(Integer::compareTo)))
				.toList();
	}

	public TargetRecord mapEntity(SourceRecord source, List<MigrationMappingRule> rules, String entity) {
		return map(source, rulesForEntity(rules, entity));
	}

	public TargetRecord map(SourceRecord source, List<MigrationMappingRule> rules) {
		Map<String, Object> target = new LinkedHashMap<>();
		for (MigrationMappingRule rule : rules) {
			if (rule == null || !rule.isEnabled()) {
				continue;
			}
			try {
				transformationEngine.apply(rule, source, target);
			} catch (TransformationException ex) {
				throw ex;
			} catch (RuntimeException ex) {
				throw new TransformationException(
						com.etp.migration.processing.MigrationErrorCodes.TRANSFORMATION_ERROR,
						rule.getTargetField(),
						"Transformation failed",
						ex);
			}
		}
		return new TargetRecord(target);
	}
}
