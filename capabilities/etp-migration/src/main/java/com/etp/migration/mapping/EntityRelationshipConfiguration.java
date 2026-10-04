package com.etp.migration.mapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Parses {@code migration_mapping.configuration} when it declares an entity relationship model.
 * Mappings that omit {@code modelType} stay flat and are left unchanged.
 */
public final class EntityRelationshipConfiguration {

	public static final String MODEL_TYPE = "ENTITY_RELATIONSHIP";
	private static final String MODEL_TYPE_FIELD = "modelType";

	private EntityRelationshipConfiguration() {
	}

	public static void validate(Map<String, Object> configuration) {
		parseIfPresent(configuration);
	}

	public static Optional<EntityRelationshipMapping> parseIfPresent(Map<String, Object> configuration) {
		if (configuration == null || !configuration.containsKey(MODEL_TYPE_FIELD)) {
			return Optional.empty();
		}
		Object modelType = configuration.get(MODEL_TYPE_FIELD);
		if (!(modelType instanceof String text) || text.isBlank()) {
			throw new InvalidEntityRelationshipConfigurationException("modelType must be ENTITY_RELATIONSHIP");
		}
		if (!MODEL_TYPE.equals(text.trim())) {
			throw new InvalidEntityRelationshipConfigurationException(
					"Unsupported modelType '" + text.trim() + "'. Supported value is ENTITY_RELATIONSHIP");
		}
		return Optional.of(parseEntityRelationship(configuration));
	}

	public static void validateRuleEntity(Map<String, Object> configuration, String entityName) {
		validateRuleEntities(configuration, java.util.Collections.singletonList(entityName));
	}

	public static void validateRuleEntities(Map<String, Object> configuration, List<String> entityNames) {
		Optional<EntityRelationshipMapping> model = parseIfPresent(configuration);
		if (model.isEmpty() || entityNames == null) {
			return;
		}
		Set<String> defined = new LinkedHashSet<>();
		for (EntityDefinition entity : model.get().entities()) {
			defined.add(entity.name());
		}
		for (String entityName : entityNames) {
			if (entityName == null || entityName.isBlank()) {
				throw new InvalidEntityRelationshipConfigurationException(
						"Mapping rule entity is required for an ENTITY_RELATIONSHIP mapping.");
			}
			String trimmed = entityName.trim();
			if (!defined.contains(trimmed)) {
				throw new InvalidEntityRelationshipConfigurationException(
						"Mapping rule entity '" + trimmed
								+ "' is not defined in the migration mapping configuration.");
			}
		}
	}

	public static EntityRelationshipMapping parse(Map<String, Object> configuration) {
		return parseIfPresent(configuration).orElseThrow(() -> new InvalidEntityRelationshipConfigurationException(
				"modelType ENTITY_RELATIONSHIP is required"));
	}

	public static Map<String, Object> toConfiguration(EntityRelationshipMapping mapping) {
		Map<String, Object> configuration = new LinkedHashMap<>();
		configuration.put(MODEL_TYPE_FIELD, MODEL_TYPE);
		configuration.put("rootEntity", mapping.rootEntity());
		configuration.put("entities", mapping.entities().stream().map(EntityRelationshipConfiguration::entityMap).toList());
		configuration.put(
				"relationships",
				mapping.relationships().stream().map(EntityRelationshipConfiguration::relationshipMap).toList());
		return configuration;
	}

	private static EntityRelationshipMapping parseEntityRelationship(Map<String, Object> configuration) {
		List<EntityDefinition> entities = entities(configuration.get("entities"));
		Map<String, EntityDefinition> byName = indexEntities(entities);
		String rootEntity = requiredText(configuration.get("rootEntity"), "rootEntity is required for ENTITY_RELATIONSHIP");
		if (!byName.containsKey(rootEntity)) {
			throw new InvalidEntityRelationshipConfigurationException(
					"rootEntity '" + rootEntity + "' does not match a configured entity");
		}
		List<RelationshipDefinition> relationships = relationships(configuration.get("relationships"), byName);
		EntityRelationshipMapping mapping = new EntityRelationshipMapping(rootEntity, entities, relationships);
		mapping.graph();
		return mapping;
	}

	private static List<EntityDefinition> entities(Object value) {
		if (!(value instanceof List<?> list) || list.isEmpty()) {
			throw new InvalidEntityRelationshipConfigurationException("entities are required");
		}
		List<EntityDefinition> entities = new ArrayList<>();
		for (Object item : list) {
			if (!(item instanceof Map<?, ?> raw)) {
				throw new InvalidEntityRelationshipConfigurationException("Entity configuration must be an object");
			}
			Map<String, Object> entity = asMap(raw);
			String name = requiredText(entity.get("name"), "Entity name is required");
			entities.add(new EntityDefinition(
					name,
					requiredText(entity.get("targetEntity"), "Entity '" + name + "' requires targetEntity"),
					requiredText(entity.get("sourceKey"), "Entity '" + name + "' requires sourceKey"),
					requiredText(entity.get("targetKey"), "Entity '" + name + "' requires targetKey"),
					targetIdentity(entity.get("targetIdentity"), name)));
		}
		return List.copyOf(entities);
	}

	private static Map<String, EntityDefinition> indexEntities(List<EntityDefinition> entities) {
		Map<String, EntityDefinition> byName = new LinkedHashMap<>();
		for (EntityDefinition entity : entities) {
			if (byName.put(entity.name(), entity) != null) {
				throw new InvalidEntityRelationshipConfigurationException("Duplicate entity name '" + entity.name() + "'");
			}
		}
		return byName;
	}

	private static List<RelationshipDefinition> relationships(Object value, Map<String, EntityDefinition> entities) {
		if (value == null) {
			return List.of();
		}
		if (!(value instanceof List<?> list)) {
			throw new InvalidEntityRelationshipConfigurationException("relationships must be a list");
		}
		List<RelationshipDefinition> relationships = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		for (Object item : list) {
			if (!(item instanceof Map<?, ?> raw)) {
				throw new InvalidEntityRelationshipConfigurationException("Relationship configuration must be an object");
			}
			relationships.add(relationship(asMap(raw), entities, seen));
		}
		return List.copyOf(relationships);
	}

	private static RelationshipDefinition relationship(
			Map<String, Object> raw,
			Map<String, EntityDefinition> entities,
			Set<String> seen) {
		String parent = requiredText(raw.get("parent"), "Relationship parent is required");
		String child = requiredText(raw.get("child"), "Relationship child is required");
		if (!entities.containsKey(parent)) {
			throw new InvalidEntityRelationshipConfigurationException(
					"Relationship references unknown entity '" + parent + "'");
		}
		if (!entities.containsKey(child)) {
			throw new InvalidEntityRelationshipConfigurationException(
					"Relationship references unknown entity '" + child + "'");
		}
		if (parent.equals(child)) {
			throw new InvalidEntityRelationshipConfigurationException(
					"Relationship from '" + parent + "' to '" + child + "' is a self-reference, which is not supported");
		}
		RelationshipCardinality cardinality = cardinality(raw.get("cardinality"), parent, child);
		List<RelationshipKey> keys = keys(raw.get("keys"), parent, child);
		String identity = parent + "\u0000" + child;
		if (!seen.add(identity)) {
			throw new InvalidEntityRelationshipConfigurationException(
					"Duplicate relationship from '" + parent + "' to '" + child + "'");
		}
		return new RelationshipDefinition(parent, child, cardinality, keys);
	}

	private static RelationshipCardinality cardinality(Object value, String parent, String child) {
		if (!(value instanceof String text) || text.isBlank()) {
			throw new InvalidEntityRelationshipConfigurationException(
					"Relationship from '" + parent + "' to '" + child + "' requires cardinality");
		}
		String token = text.trim();
		try {
			return RelationshipCardinality.valueOf(token);
		} catch (IllegalArgumentException ex) {
			throw new InvalidEntityRelationshipConfigurationException(
					"Unsupported cardinality '" + token
							+ "'. Supported values are ONE_TO_ONE, ONE_TO_MANY, and MANY_TO_ONE");
		}
	}

	private static List<RelationshipKey> keys(Object value, String parent, String child) {
		if (!(value instanceof List<?> list) || list.isEmpty()) {
			throw new InvalidEntityRelationshipConfigurationException(
					"Relationship from '" + parent + "' to '" + child + "' requires at least one key");
		}
		List<RelationshipKey> keys = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		for (Object item : list) {
			if (!(item instanceof Map<?, ?> raw)) {
				throw new InvalidEntityRelationshipConfigurationException(
						"Relationship from '" + parent + "' to '" + child + "' has an invalid key");
			}
			Map<String, Object> key = asMap(raw);
			String parentField = requiredText(
					key.get("parentField"),
					"Relationship from '" + parent + "' to '" + child + "' requires parentField");
			String childField = requiredText(
					key.get("childField"),
					"Relationship from '" + parent + "' to '" + child + "' requires childField");
			if (!seen.add(parentField + "\u0000" + childField)) {
				throw new InvalidEntityRelationshipConfigurationException(
						"Relationship from '" + parent + "' to '" + child + "' has a duplicate key '"
								+ parentField + "' -> '" + childField + "'");
			}
			keys.add(new RelationshipKey(parentField, childField));
		}
		return List.copyOf(keys);
	}

	private static Map<String, Object> entityMap(EntityDefinition entity) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("name", entity.name());
		map.put("targetEntity", entity.targetEntity());
		map.put("sourceKey", entity.sourceKey());
		map.put("targetKey", entity.targetKey());
		if (entity.targetIdentity() != null) {
			map.put("targetIdentity", targetIdentityMap(entity.targetIdentity()));
		}
		return map;
	}

	private static TargetIdentitySpec targetIdentity(Object value, String entityName) {
		if (value == null) {
			return null;
		}
		if (!(value instanceof Map<?, ?> raw)) {
			throw new InvalidEntityRelationshipConfigurationException(
					"Entity '" + entityName + "' targetIdentity must be an object");
		}
		Map<String, Object> identity = asMap(raw);
		if (identity.containsKey("start")) {
			throw new InvalidEntityRelationshipConfigurationException(
					"Entity '" + entityName + "' targetIdentity does not accept start");
		}
		String strategy = requiredText(
				identity.get("strategy"),
				"Entity '" + entityName + "' targetIdentity strategy is required");
		String prefix = requiredText(
				identity.get("prefix"),
				"Entity '" + entityName + "' targetIdentity prefix is required");
		if (!prefix.matches("[A-Za-z][A-Za-z0-9-]*")) {
			throw new InvalidEntityRelationshipConfigurationException(
					"Entity '" + entityName + "' targetIdentity prefix is invalid");
		}
		if (TargetIdentitySpec.PREFIX_SEQUENCE.equals(strategy)) {
			int sequenceLength = sequenceLength(identity.get("sequenceLength"), entityName);
			return new TargetIdentitySpec(strategy, prefix, sequenceLength);
		}
		if (TargetIdentitySpec.PREFIX_SOURCE_VALUE.equals(strategy)) {
			if (identity.get("sequenceLength") != null) {
				throw new InvalidEntityRelationshipConfigurationException(
						"Entity '" + entityName + "' PREFIX_SOURCE_VALUE does not use sequenceLength");
			}
			return new TargetIdentitySpec(strategy, prefix, null);
		}
		throw new InvalidEntityRelationshipConfigurationException(
				"Entity '" + entityName + "' has an unsupported targetIdentity strategy");
	}

	private static int sequenceLength(Object value, String entityName) {
		if (!(value instanceof Number number)) {
			throw new InvalidEntityRelationshipConfigurationException(
					"Entity '" + entityName + "' targetIdentity sequenceLength is required");
		}
		int length = number.intValue();
		if (length < 1 || length > 18) {
			throw new InvalidEntityRelationshipConfigurationException(
					"Entity '" + entityName + "' targetIdentity sequenceLength is invalid");
		}
		return length;
	}

	private static Map<String, Object> targetIdentityMap(TargetIdentitySpec spec) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("strategy", spec.strategy());
		map.put("prefix", spec.prefix());
		if (spec.sequenceLength() != null) {
			map.put("sequenceLength", spec.sequenceLength());
		}
		return map;
	}

	private static Map<String, Object> relationshipMap(RelationshipDefinition relationship) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("parent", relationship.parent());
		map.put("child", relationship.child());
		map.put("cardinality", relationship.cardinality().name());
		map.put("keys", relationship.keys().stream().map(key -> {
			Map<String, Object> keyMap = new LinkedHashMap<>();
			keyMap.put("parentField", key.parentField());
			keyMap.put("childField", key.childField());
			return keyMap;
		}).toList());
		return map;
	}

	private static String requiredText(Object value, String message) {
		if (!(value instanceof String text) || text.isBlank()) {
			throw new InvalidEntityRelationshipConfigurationException(message);
		}
		return text.trim();
	}

	private static Map<String, Object> asMap(Map<?, ?> raw) {
		Map<String, Object> map = new LinkedHashMap<>();
		raw.forEach((key, value) -> map.put(String.valueOf(key), value));
		return map;
	}
}
