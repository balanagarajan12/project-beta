package com.etp.migration.connector.target.postgresql;

import com.etp.migration.connector.target.MigrationTargetReconciliationProvider;
import com.etp.migration.connector.target.StagedEntityView;
import com.etp.migration.connector.target.StagedRelationshipView;
import com.etp.migration.connector.target.TargetInfrastructureException;
import com.etp.migration.connector.target.TargetVerification;
import com.etp.migration.processing.MigrationErrorCodes;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class PostgreSqlTargetReconciliationProvider implements MigrationTargetReconciliationProvider {

	private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {
	};

	private final PostgreSqlTargetProperties properties;
	private final DataSource dataSource;
	private final PostgreSqlConnectionRegistry connections;
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Autowired
	public PostgreSqlTargetReconciliationProvider(
			PostgreSqlTargetProperties properties,
			@Autowired(required = false) @Qualifier(PostgreSqlTargetClientConfig.DATASOURCE_BEAN) DataSource dataSource,
			@Autowired(required = false) PostgreSqlConnectionRegistry connections) {
		this.properties = properties;
		this.connections = connections;
		this.dataSource = dataSource;
	}

	@Override
	public String targetType() {
		return PostgreSqlTargetConnector.TARGET_TYPE;
	}

	@Override
	public boolean entityProjectionAvailable() {
		return dataSource != null;
	}

	@Override
	public void streamStagedEntities(
			UUID migrationRunId,
			Map<String, Object> definitionConfiguration,
			Consumer<StagedEntityView> consumer) {
		if (consumer == null) {
			return;
		}
		DataSource source = PostgreSqlConnectionRegistry.stagingOf(connections, dataSource, definitionConfiguration);
		PostgreSqlStagingTarget.Resolved staging = PostgreSqlStagingTarget.resolve(definitionConfiguration, properties);
		String sql = "SELECT target_payload::text FROM " + PostgreSqlStagingTarget.quoted(staging)
				+ " WHERE migration_run_id = ?";
		try (Connection connection = source.getConnection();
				PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setObject(1, migrationRunId);
			try (ResultSet resultSet = statement.executeQuery()) {
				while (resultSet.next()) {
					StagedEntityView view = readView(resultSet.getString(1));
					if (view != null) {
						consumer.accept(view);
					}
				}
			}
		} catch (SQLException ex) {
			throw new TargetInfrastructureException(
					MigrationErrorCodes.TARGET_DATABASE_CONNECTION_ERROR,
					"PostgreSQL staging projection failed",
					ex);
		}
	}

	@Override
	public TargetVerification verify(UUID migrationRunId, Map<String, Object> definitionConfiguration) {
		DataSource source = PostgreSqlConnectionRegistry.stagingOf(connections, dataSource, definitionConfiguration);
		PostgreSqlStagingTarget.Resolved staging = PostgreSqlStagingTarget.resolve(definitionConfiguration, properties);
		long count = countStagingRows(source, staging, migrationRunId);
		Map<String, Object> attributes = new LinkedHashMap<>();
		attributes.put("targetSchema", staging.schema());
		attributes.put("targetTable", staging.table());
		attributes.put("mergeStrategy", staging.mergeStrategy());
		return new TargetVerification(
				true,
				count,
				"STAGING",
				false,
				"PostgreSQL mergeStrategy=NONE verifies staging ingestion only",
				attributes);
	}

	private StagedEntityView readView(String json) {
		if (json == null || json.isBlank()) {
			return null;
		}
		try {
			Map<String, Object> payload = objectMapper.readValue(json, PAYLOAD_TYPE);
			Object entity = payload.get("entity");
			if (!(entity instanceof String entityName) || entityName.isBlank()) {
				return null;
			}
			List<StagedRelationshipView> relationships = new ArrayList<>();
			Object rawRelationships = payload.get("relationships");
			if (rawRelationships instanceof List<?> list) {
				for (Object item : list) {
					if (!(item instanceof Map<?, ?> map)) {
						continue;
					}
					Object parentEntity = map.get("parentEntity");
					if (!(parentEntity instanceof String parentName) || parentName.isBlank()) {
						continue;
					}
					relationships.add(new StagedRelationshipView(
							parentName,
							text(map.get("parentSourceIdentity")),
							text(map.get("parentTargetIdentity"))));
				}
			}
			return new StagedEntityView(entityName, text(payload.get("sourceIdentity")), text(payload.get("targetIdentity")), relationships);
		} catch (Exception ex) {
			throw new TargetInfrastructureException(
					MigrationErrorCodes.TARGET_INVALID_RESPONSE,
					"PostgreSQL staging payload could not be projected",
					ex);
		}
	}

	private static String text(Object value) {
		return value == null ? null : value.toString();
	}

	private long countStagingRows(DataSource source, PostgreSqlStagingTarget.Resolved staging, UUID migrationRunId) {
		String sql = "SELECT COUNT(*) FROM " + PostgreSqlStagingTarget.quoted(staging) + " WHERE migration_run_id = ?";
		try (Connection connection = source.getConnection();
				PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setObject(1, migrationRunId);
			try (ResultSet resultSet = statement.executeQuery()) {
				resultSet.next();
				return resultSet.getLong(1);
			}
		} catch (SQLException ex) {
			throw new TargetInfrastructureException(
					MigrationErrorCodes.TARGET_DATABASE_CONNECTION_ERROR,
					"PostgreSQL staging count failed",
					ex);
		}
	}
}
