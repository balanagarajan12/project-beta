package com.etp.migration.connector.target.uam;

import com.etp.migration.connector.target.LoadableTargetRecord;
import com.etp.migration.connector.target.MigrationTargetContext;
import com.etp.migration.connector.target.postgresql.PostgreSqlStagedEntityReader;
import com.etp.migration.entity.LoadMode;
import com.etp.migration.identity.GeneratedTargetIdentityLookup;
import com.etp.migration.identity.GeneratedTargetIdentityService;
import com.etp.migration.identity.TargetIdentityLookup;
import com.etp.migration.mapping.EntityRelationshipConfiguration;
import com.etp.migration.mapping.EntityRelationshipMapping;
import com.etp.migration.processing.TargetRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Promotion-time bridge from staged entity records to the canonical loader.
 * Reads one staged batch, fills target identities through the existing identity service,
 * fills parent target identities from those mappings, then calls the existing loader.
 * Staging rows are not updated.
 */
@Component
public class UamStagingPromotion {

	private final PostgreSqlStagedEntityReader stagedEntityReader;
	private final GeneratedTargetIdentityService identityService;
	private final UamPostgreSqlTargetConnector connector;

	public UamStagingPromotion(
			PostgreSqlStagedEntityReader stagedEntityReader,
			GeneratedTargetIdentityService identityService,
			UamPostgreSqlTargetConnector connector) {
		this.stagedEntityReader = stagedEntityReader;
		this.identityService = identityService;
		this.connector = connector;
	}

	public static boolean configured(Map<String, Object> configuration) {
		if (configuration == null || !(configuration.get("target") instanceof Map<?, ?> target)) {
			return false;
		}
		Object mode = target.get("mode");
		return mode != null && UamCanonicalRows.MODE.equals(mode.toString().trim());
	}

	public static boolean promotionEnabled(Map<String, Object> configuration) {
		if (configuration == null || !(configuration.get("promotion") instanceof Map<?, ?> promotion)) {
			return false;
		}
		Object enabled = promotion.get("enabled");
		if (enabled instanceof Boolean flag) {
			return flag;
		}
		return enabled != null && Boolean.parseBoolean(enabled.toString().trim());
	}

	public void promoteBatch(
			UUID migrationRunId,
			Long migrationBatchId,
			Map<String, Object> definitionConfiguration,
			Map<String, Object> mappingConfiguration) {
		List<LoadableTargetRecord> staged = stagedEntityReader.read(
				migrationRunId, migrationBatchId, definitionConfiguration);
		if (staged.isEmpty()) {
			return;
		}
		EntityRelationshipMapping mapping = EntityRelationshipConfiguration.parse(mappingConfiguration);
		List<LoadableTargetRecord> resolved = resolve(migrationRunId, definitionConfiguration, mapping, staged);
		connector.load(context(migrationRunId, migrationBatchId, definitionConfiguration), resolved);
	}

	private List<LoadableTargetRecord> resolve(
			UUID migrationRunId,
			Map<String, Object> definitionConfiguration,
			EntityRelationshipMapping mapping,
			List<LoadableTargetRecord> staged) {
		TargetIdentityLookup lookup = GeneratedTargetIdentityLookup.decorate(
				identityService,
				migrationRunId,
				mapping,
				definitionConfiguration,
				(entityName, sourceIdentities) -> Map.of());
		Map<String, LinkedHashSet<String>> sources = sourceIdentities(staged);
		Map<String, Map<String, String>> allocated = new LinkedHashMap<>();
		for (String entityName : allocationOrder(definitionConfiguration, sources)) {
			LinkedHashSet<String> identities = sources.get(entityName);
			if (identities == null || identities.isEmpty()) {
				continue;
			}
			Map<String, String> assigned = lookup.lookup(entityName, List.copyOf(identities));
			allocated.put(entityName, assigned == null ? Map.of() : assigned);
		}
		List<LoadableTargetRecord> resolved = new ArrayList<>(staged.size());
		for (LoadableTargetRecord record : staged) {
			resolved.add(apply(record, allocated));
		}
		return resolved;
	}

	private static List<String> allocationOrder(
			Map<String, Object> configuration,
			Map<String, LinkedHashSet<String>> sources) {
		LinkedHashSet<String> order = new LinkedHashSet<>(stringList(configuration.get("loadOrder")));
		order.addAll(sources.keySet());
		return List.copyOf(order);
	}

	private static Map<String, LinkedHashSet<String>> sourceIdentities(List<LoadableTargetRecord> staged) {
		Map<String, LinkedHashSet<String>> sources = new LinkedHashMap<>();
		for (LoadableTargetRecord record : staged) {
			Map<String, Object> fields = record.targetRecord().fields();
			add(sources, text(fields.get("entity")), text(fields.get("sourceIdentity")));
			if (fields.get("relationships") instanceof List<?> links) {
				for (Object link : links) {
					if (link instanceof Map<?, ?> relationship) {
						add(sources, text(relationship.get("parentEntity")), text(relationship.get("parentSourceIdentity")));
					}
				}
			}
		}
		return sources;
	}

	private static LoadableTargetRecord apply(
			LoadableTargetRecord record,
			Map<String, Map<String, String>> allocated) {
		Map<String, Object> fields = new LinkedHashMap<>();
		record.targetRecord().fields().forEach((key, value) -> fields.put(key, copyValue(key, value, allocated)));
		if (text(fields.get("targetIdentity")) == null) {
			String resolved = resolve(allocated, text(fields.get("entity")), text(fields.get("sourceIdentity")));
			if (resolved != null) {
				fields.put("targetIdentity", resolved);
			}
		}
		return new LoadableTargetRecord(record.sourceRecordNumber(), new TargetRecord(fields), record.idempotencyKey());
	}

	private static Object copyValue(String key, Object value, Map<String, Map<String, String>> allocated) {
		if (!"relationships".equals(key) || !(value instanceof List<?> links)) {
			return value;
		}
		List<Object> copied = new ArrayList<>(links.size());
		for (Object link : links) {
			if (!(link instanceof Map<?, ?> raw)) {
				copied.add(link);
				continue;
			}
			Map<String, Object> relationship = new LinkedHashMap<>();
			raw.forEach((name, item) -> {
				if (name != null) {
					relationship.put(name.toString(), item);
				}
			});
			if (text(relationship.get("parentTargetIdentity")) == null) {
				String resolved = resolve(
						allocated,
						text(relationship.get("parentEntity")),
						text(relationship.get("parentSourceIdentity")));
				if (resolved != null) {
					relationship.put("parentTargetIdentity", resolved);
				}
			}
			copied.add(relationship);
		}
		return copied;
	}

	private static MigrationTargetContext context(
			UUID migrationRunId,
			Long migrationBatchId,
			Map<String, Object> configuration) {
		return new MigrationTargetContext(
				migrationRunId,
				migrationBatchId,
				null,
				UamPostgreSqlTargetConnector.TARGET_TYPE,
				null,
				LoadMode.BULK,
				configuration);
	}

	private static void add(Map<String, LinkedHashSet<String>> sources, String entityName, String sourceIdentity) {
		if (entityName == null || sourceIdentity == null) {
			return;
		}
		sources.computeIfAbsent(entityName, ignored -> new LinkedHashSet<>()).add(sourceIdentity);
	}

	private static String resolve(
			Map<String, Map<String, String>> allocated,
			String entityName,
			String sourceIdentity) {
		if (entityName == null || sourceIdentity == null) {
			return null;
		}
		Map<String, String> identities = allocated.get(entityName);
		if (identities == null) {
			return null;
		}
		String targetIdentity = identities.get(sourceIdentity);
		return targetIdentity == null || targetIdentity.isBlank() ? null : targetIdentity;
	}

	private static List<String> stringList(Object value) {
		if (!(value instanceof List<?> list)) {
			return List.of();
		}
		List<String> values = new ArrayList<>();
		for (Object item : list) {
			String text = text(item);
			if (text != null) {
				values.add(text);
			}
		}
		return values;
	}

	private static String text(Object value) {
		if (value == null) {
			return null;
		}
		String text = value.toString().trim();
		return text.isEmpty() ? null : text;
	}
}
