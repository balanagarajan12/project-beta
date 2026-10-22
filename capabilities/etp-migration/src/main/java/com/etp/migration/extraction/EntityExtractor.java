package com.etp.migration.extraction;

import com.etp.migration.entity.MigrationMappingRule;
import com.etp.migration.mapping.EntityDefinition;
import com.etp.migration.mapping.EntityRelationshipMapping;
import com.etp.migration.mapping.RelationshipDefinition;
import com.etp.migration.mapping.RelationshipKey;
import com.etp.migration.processing.SourceRecord;
import com.etp.migration.transformation.MappingEngine;
import com.etp.migration.transformation.RuleSourceFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Turns one physical {@link SourceRecord} into logical entity records.
 * Each call is independent. Nothing from previous rows is retained.
 */
@Component
public class EntityExtractor {

	private final MappingEngine mappingEngine;

	public EntityExtractor(MappingEngine mappingEngine) {
		this.mappingEngine = mappingEngine;
	}

	public EntityExtraction extract(
			SourceRecord source,
			EntityRelationshipMapping mapping,
			List<MigrationMappingRule> rules) {
		long started = com.etp.migration.processing.PipelineTiming.start();
		try {
			return extractMeasured(source, mapping, rules);
		} finally {
			com.etp.migration.processing.PipelineTiming.stop(
					com.etp.migration.processing.PipelineTiming.Stage.EXTRACTION,
					started);
		}
	}

	private EntityExtraction extractMeasured(
			SourceRecord source,
			EntityRelationshipMapping mapping,
			List<MigrationMappingRule> rules) {
		if (source == null) {
			throw new IllegalArgumentException("source is required");
		}
		if (mapping == null) {
			throw new IllegalArgumentException("mapping is required");
		}
		List<EntityRecord> records = new ArrayList<>();
		List<EntityExtractionFailure> failures = new ArrayList<>();
		Map<String, Object> physical = source.fields() == null ? Map.of() : source.fields();
		for (EntityDefinition entity : mapping.entities()) {
			List<MigrationMappingRule> entityRules = mappingEngine.rulesForEntity(rules, entity.name());
			Set<String> sourceFields = sourceFields(entity, entityRules);
			if (entity.sourceKey() == null) {
				// A null source key means this entity is the source row. Constant-only entities,
				// such as a parent whose attributes are defaults, still belong to that row.
				// Entities that read source columns are emitted only when one of those columns has a value.
				if (hasOtherValues(physical, sourceFields, null) || derivedFromEverySourceRecord(entityRules)) {
					records.add(new EntityRecord(
							entity.name(),
							source.recordNumber(),
							Long.toString(source.recordNumber()),
							copyFields(physical, copiedFields(sourceFields, entity.name(), mapping))));
				}
				continue;
			}
			if (present(physical, entity.sourceKey())) {
				records.add(new EntityRecord(
						entity.name(),
						source.recordNumber(),
						text(physical.get(entity.sourceKey())).trim(),
						copyFields(physical, copiedFields(sourceFields, entity.name(), mapping))));
				continue;
			}
			if (hasOtherValues(physical, sourceFields, entity.sourceKey())) {
				failures.add(failure(source, entity));
			}
		}
		return new EntityExtraction(records, failures);
	}

	private static Set<String> copiedFields(Set<String> sourceFields, String entityName, EntityRelationshipMapping mapping) {
		Set<String> fields = new LinkedHashSet<>(sourceFields);
		for (RelationshipDefinition relationship : mapping.relationships()) {
			if (entityName.equals(relationship.parent())) {
				for (RelationshipKey key : relationship.keys()) {
					add(fields, key.parentField());
				}
			}
			if (entityName.equals(relationship.child())) {
				for (RelationshipKey key : relationship.keys()) {
					add(fields, key.childField());
				}
			}
		}
		return fields;
	}

	private static Set<String> sourceFields(EntityDefinition entity, List<MigrationMappingRule> rules) {
		Set<String> fields = new LinkedHashSet<>();
		add(fields, entity.sourceKey());
		for (MigrationMappingRule rule : rules) {
			for (String column : RuleSourceFields.columns(rule)) {
				add(fields, column);
			}
			Map<String, Object> configuration = rule.getConfiguration();
			if (configuration == null) {
				continue;
			}
			Object condition = configuration.get("condition");
			if (condition instanceof Map<?, ?> conditionMap) {
				Object conditionField = conditionMap.get("sourceField");
				if (conditionField instanceof String text) {
					add(fields, text);
				}
			}
		}
		return fields;
	}

	private static Map<String, Object> copyFields(Map<String, Object> physical, Set<String> sourceFields) {
		Map<String, Object> copy = new LinkedHashMap<>();
		for (String field : sourceFields) {
			if (physical.containsKey(field) && physical.get(field) != null) {
				copy.put(field, physical.get(field));
			}
		}
		return copy;
	}

	private static boolean derivedFromEverySourceRecord(List<MigrationMappingRule> rules) {
		boolean enabled = false;
		for (MigrationMappingRule rule : rules) {
			if (rule == null || !rule.isEnabled()) {
				continue;
			}
			enabled = true;
			if (readsSource(rule)) {
				return false;
			}
		}
		return enabled;
	}

	private static boolean readsSource(MigrationMappingRule rule) {
		return !RuleSourceFields.columns(rule).isEmpty();
	}

	private static boolean hasOtherValues(Map<String, Object> physical, Set<String> sourceFields, String sourceKey) {
		for (String field : sourceFields) {
			if (field.equals(sourceKey)) {
				continue;
			}
			if (present(physical, field)) {
				return true;
			}
		}
		return false;
	}

	private static boolean present(Map<String, Object> fields, String name) {
		if (name == null || name.isBlank() || !fields.containsKey(name)) {
			return false;
		}
		String value = text(fields.get(name));
		return value != null && !value.isBlank();
	}

	private static String text(Object value) {
		return value == null ? null : String.valueOf(value);
	}

	private static void add(Set<String> fields, String name) {
		if (name != null && !name.isBlank()) {
			fields.add(name.trim());
		}
	}

	private static EntityExtractionFailure failure(SourceRecord source, EntityDefinition entity) {
		String sourceKey = entity.sourceKey() == null ? "" : entity.sourceKey();
		return new EntityExtractionFailure(
				source.recordNumber(),
				entity.name(),
				sourceKey,
				"Unable to extract entity '" + entity.name()
						+ "' from source record " + source.recordNumber()
						+ ": source key '" + sourceKey + "' is missing.");
	}
}
