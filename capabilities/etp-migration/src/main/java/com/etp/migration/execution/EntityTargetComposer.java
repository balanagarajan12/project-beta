package com.etp.migration.execution;

import com.etp.migration.entity.MigrationMappingRule;
import com.etp.migration.extraction.EntityRecord;
import com.etp.migration.identity.ParentIdentityReference;
import com.etp.migration.identity.ResolvedIdentity;
import com.etp.migration.mapping.EntityDefinition;
import com.etp.migration.mapping.EntityRelationshipMapping;
import com.etp.migration.mapping.RelationshipDefinition;
import com.etp.migration.mapping.RelationshipKey;
import com.etp.migration.processing.ProcessingError;
import com.etp.migration.processing.TargetRecord;
import com.etp.migration.processing.TransformationException;
import com.etp.migration.transformation.MappingEngine;
import com.etp.migration.validation.ValidationEngine;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Maps one entity record, validates it, and builds a generic target payload.
 * Relationship values use resolved target identities.
 */
@Component
public class EntityTargetComposer {

	private final MappingEngine mappingEngine;
	private final ValidationEngine validationEngine;

	public EntityTargetComposer(MappingEngine mappingEngine, ValidationEngine validationEngine) {
		this.mappingEngine = mappingEngine;
		this.validationEngine = validationEngine;
	}

	public TargetRecord map(EntityRecord record, List<MigrationMappingRule> rules) {
		long started = com.etp.migration.processing.PipelineTiming.start();
		try {
			return mappingEngine.mapEntity(record.toSourceRecord(), rules, record.entityName());
		} finally {
			com.etp.migration.processing.PipelineTiming.stop(
					com.etp.migration.processing.PipelineTiming.Stage.MAPPING,
					started);
		}
	}

	public List<ProcessingError> validate(TargetRecord target, List<MigrationMappingRule> rules, String entityName) {
		long started = com.etp.migration.processing.PipelineTiming.start();
		try {
			return validationEngine.validate(target, mappingEngine.rulesForEntity(rules, entityName));
		} finally {
			com.etp.migration.processing.PipelineTiming.stop(
					com.etp.migration.processing.PipelineTiming.Stage.VALIDATION,
					started);
		}
	}

	public Map<String, Object> payload(
			EntityRelationshipMapping mapping,
			ResolvedIdentity resolved,
			EntityRecord record,
			TargetRecord attributes) {
		return payload(
				mapping,
				resolved.source().entityName(),
				resolved.source().sourceIdentity(),
				resolved.targetIdentity(),
				resolved.targetEntity(),
				resolved.sourceKey(),
				resolved.targetKey(),
				record,
				attributes,
				resolved.parents());
	}

	public Map<String, Object> payload(
			EntityRelationshipMapping mapping,
			String entityName,
			String sourceIdentity,
			String targetIdentity,
			EntityRecord record,
			TargetRecord attributes,
			List<ParentIdentityReference> parents) {
		EntityDefinition definition = definition(mapping, entityName);
		return payload(
				mapping,
				entityName,
				sourceIdentity,
				targetIdentity,
				definition == null ? null : definition.targetEntity(),
				definition == null ? null : definition.sourceKey(),
				definition == null ? null : definition.targetKey(),
				record,
				attributes,
				parents);
	}

	private Map<String, Object> payload(
			EntityRelationshipMapping mapping,
			String entityName,
			String sourceIdentity,
			String targetIdentity,
			String targetEntity,
			String sourceKey,
			String targetKey,
			EntityRecord record,
			TargetRecord attributes,
			List<ParentIdentityReference> parents) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("entity", entityName);
		payload.put("targetEntity", targetEntity);
		payload.put("sourceIdentity", sourceIdentity);
		payload.put("targetIdentity", targetIdentity);
		payload.put("sourceKey", sourceKey);
		payload.put("targetKey", targetKey);
		payload.put("sourceRecordNumber", record.sourceRecordNumber());
		payload.put("attributes", attributes.fields() == null ? Map.of() : attributes.fields());
		payload.put("relationships", relationships(mapping, entityName, parents));
		return payload;
	}

	public TransformationException asTransformation(RuntimeException ex) {
		if (ex instanceof TransformationException transformation) {
			return transformation;
		}
		return null;
	}

	private static EntityDefinition definition(EntityRelationshipMapping mapping, String entityName) {
		if (mapping == null || entityName == null) {
			return null;
		}
		return mapping.entities().stream()
				.filter(entity -> entityName.equals(entity.name()))
				.findFirst()
				.orElse(null);
	}

	private static List<Map<String, Object>> relationships(
			EntityRelationshipMapping mapping,
			String entityName,
			List<ParentIdentityReference> parents) {
		List<Map<String, Object>> links = new ArrayList<>();
		if (parents == null) {
			return links;
		}
		for (ParentIdentityReference parent : parents) {
			if (parent.source() == null) {
				continue;
			}
			EntityDefinition parentDefinition = definition(mapping, parent.source().entityName());
			Map<String, Object> link = new LinkedHashMap<>();
			link.put("parentEntity", parent.source().entityName());
			link.put("parentSourceIdentity", parent.source().sourceIdentity());
			link.put("parentTargetIdentity", parent.targetIdentity());
			if (parentDefinition != null) {
				link.put("parentTargetEntity", parentDefinition.targetEntity());
			}
			link.put("keys", keys(mapping, parent.source().entityName(), entityName));
			links.add(link);
		}
		return links;
	}

	private static List<Map<String, String>> keys(
			EntityRelationshipMapping mapping,
			String parentEntity,
			String childEntity) {
		List<Map<String, String>> keys = new ArrayList<>();
		for (RelationshipDefinition relationship : mapping.relationships()) {
			if (!parentEntity.equals(relationship.parent()) || !childEntity.equals(relationship.child())) {
				continue;
			}
			for (RelationshipKey key : relationship.keys()) {
				Map<String, String> stored = new LinkedHashMap<>();
				stored.put("parentField", key.parentField());
				stored.put("childField", key.childField());
				keys.add(stored);
			}
		}
		return keys;
	}
}
