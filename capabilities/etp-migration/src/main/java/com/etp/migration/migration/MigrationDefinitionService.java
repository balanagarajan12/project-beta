package com.etp.migration.migration;

import com.etp.migration.config.AuditActor;
import com.etp.migration.connector.source.InvalidSourceConfigurationException;
import com.etp.migration.connector.source.MigrationSourceConnectorRegistry;
import com.etp.migration.connector.source.csv.SourceLayout;
import com.etp.migration.connector.source.csv.SourceLayoutConfiguration;
import com.etp.migration.connector.source.csv.SourceLayoutType;
import com.etp.migration.connector.target.postgresql.PromotionTargetConfiguration;
import com.etp.migration.connector.target.uam.UamStagingPromotion;
import com.etp.migration.execution.ExecutionMode;
import com.etp.migration.mapping.EntityRelationshipConfiguration;
import com.etp.migration.entity.LoadMode;
import com.etp.migration.entity.MigrationConfigStatus;
import com.etp.migration.entity.MigrationDefinition;
import com.etp.migration.entity.MigrationMapping;
import com.etp.migration.entity.MigrationMappingRule;
import com.etp.migration.entity.MigrationSource;
import com.etp.migration.migration.dto.CreateMigrationDefinitionRequest;
import com.etp.migration.migration.dto.MigrationDefinitionResponse;
import com.etp.migration.migration.dto.PagedResponse;
import com.etp.migration.repository.MigrationDefinitionRepository;
import com.etp.migration.repository.MigrationMappingRepository;
import com.etp.migration.repository.MigrationMappingRuleRepository;
import com.etp.migration.repository.MigrationSourceRepository;
import com.etp.migration.run.InvalidStateTransitionException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MigrationDefinitionService {

	static final int DEFAULT_BATCH_SIZE = 10_000;
	static final int DEFAULT_PAGE = 0;
	static final int DEFAULT_PAGE_SIZE = 20;
	static final int MAX_PAGE_SIZE = 100;
	private final MigrationDefinitionRepository migrationDefinitionRepository;
	private final MigrationMappingRepository migrationMappingRepository;
	private final MigrationMappingRuleRepository migrationMappingRuleRepository;
	private final MigrationSourceRepository migrationSourceRepository;
	private final MigrationSourceConnectorRegistry sourceConnectorRegistry;

	public MigrationDefinitionService(
			MigrationDefinitionRepository migrationDefinitionRepository,
			MigrationMappingRepository migrationMappingRepository,
			MigrationMappingRuleRepository migrationMappingRuleRepository,
			MigrationSourceRepository migrationSourceRepository,
			MigrationSourceConnectorRegistry sourceConnectorRegistry) {
		this.migrationDefinitionRepository = migrationDefinitionRepository;
		this.migrationMappingRepository = migrationMappingRepository;
		this.migrationMappingRuleRepository = migrationMappingRuleRepository;
		this.migrationSourceRepository = migrationSourceRepository;
		this.sourceConnectorRegistry = sourceConnectorRegistry;
	}

	@Transactional
	public MigrationDefinitionResponse create(CreateMigrationDefinitionRequest request) {
		if (migrationDefinitionRepository.existsByNameAndVersion(request.name(), request.version())) {
			throw new DuplicateMigrationDefinitionException(request.name(), request.version());
		}

		MigrationMapping mapping = resolveMapping(request.mappingId());
		Instant now = Instant.now();

		MigrationDefinition definition = new MigrationDefinition();
		definition.setId(UUID.randomUUID());
		definition.setName(request.name());
		definition.setVersion(request.version());
		definition.setDescription(request.description());
		definition.setSourceType(request.sourceType());
		definition.setTargetType(request.targetType());
		definition.setTargetEntity(request.targetEntity());
		definition.setMapping(mapping);
		definition.setBatchSize(request.batchSize() != null ? request.batchSize() : DEFAULT_BATCH_SIZE);
		definition.setLoadMode(request.loadMode() != null ? request.loadMode() : LoadMode.BULK);
		definition.setStatus(MigrationConfigStatus.DRAFT);
		definition.setConfiguration(copyConfiguration(request.configuration()));
		validatePromotionConfiguration(definition.getConfiguration());
		PostgreSqlBulkTargetRules.require(
				definition.getTargetType(),
				definition.getLoadMode(),
				definition.getConfiguration(),
				"configuration");
		definition.setCreatedAt(now);
		definition.setUpdatedAt(now);
		String actor = AuditActor.current();
		definition.setCreatedBy(actor);
		definition.setUpdatedBy(actor);

		try {
			return toResponse(migrationDefinitionRepository.save(definition));
		} catch (DataIntegrityViolationException ex) {
			if (isUniqueNameVersionViolation(ex)) {
				throw new DuplicateMigrationDefinitionException(request.name(), request.version());
			}
			throw ex;
		}
	}

	@Transactional(readOnly = true)
	public MigrationDefinitionResponse getById(UUID id) {
		return migrationDefinitionRepository.findById(id)
				.map(this::toResponse)
				.orElseThrow(() -> new MigrationDefinitionNotFoundException(id));
	}

	@Transactional
	public MigrationDefinitionResponse activate(UUID migrationId) {
		MigrationDefinition definition = migrationDefinitionRepository.findByIdForUpdate(migrationId)
				.orElseThrow(() -> new MigrationDefinitionNotFoundException(migrationId));
		if (definition.getStatus() != MigrationConfigStatus.ACTIVE
				&& definition.getStatus() != MigrationConfigStatus.DRAFT) {
			throw new InvalidStateTransitionException(
					"Migration definition " + migrationId + " cannot be activated from " + definition.getStatus());
		}
		if (definition.getStatus() == MigrationConfigStatus.DRAFT) {
			validateReadyForActivation(definition);
		}
		deactivateOtherActiveVersions(definition);
		if (definition.getStatus() == MigrationConfigStatus.ACTIVE) {
			return toResponse(definition);
		}
		Instant now = Instant.now();
		definition.setStatus(MigrationConfigStatus.ACTIVE);
		definition.setUpdatedAt(now);
		definition.setUpdatedBy(AuditActor.current());
		return toResponse(definition);
	}

	@Transactional(readOnly = true)
	public PagedResponse<MigrationDefinitionResponse> list(int page, int size) {
		int safePage = Math.max(page, DEFAULT_PAGE);
		int safeSize = size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
		Page<MigrationDefinition> result = migrationDefinitionRepository.findAll(
				PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt")));
		return new PagedResponse<>(
				result.getContent().stream().map(this::toResponse).toList(),
				result.getNumber(),
				result.getSize(),
				result.getTotalElements(),
				result.getTotalPages());
	}

	private static void validatePromotionConfiguration(Map<String, Object> configuration) {
		if (UamStagingPromotion.configured(configuration) && UamStagingPromotion.promotionEnabled(configuration)) {
			return;
		}
		PromotionTargetConfiguration.parse(configuration);
	}

	private void validateReadyForActivation(MigrationDefinition definition) {
		if (definition.getBatchSize() == null || definition.getBatchSize() <= 0) {
			throw new IllegalArgumentException("batchSize must be greater than 0");
		}
		if (definition.getLoadMode() == null) {
			throw new IllegalArgumentException("loadMode is required");
		}
		validatePromotionConfiguration(definition.getConfiguration());
		PostgreSqlBulkTargetRules.require(
				definition.getTargetType(),
				definition.getLoadMode(),
				definition.getConfiguration(),
				"configuration");

		MigrationMapping mapping = definition.getMapping();
		if (mapping == null) {
			throw new IllegalArgumentException("Migration mapping is not configured");
		}
		if (ExecutionMode.entityRelationship(mapping.getConfiguration())) {
			MigrationModelRules.validate(definition.getTargetEntity(), mapping.getConfiguration());
		}
		EntityRelationshipConfiguration.validate(mapping.getConfiguration());
		if (migrationMappingRuleRepository
				.findByMapping_IdAndEnabledTrueOrderBySequenceNoAsc(mapping.getId())
				.isEmpty()) {
			throw new IllegalArgumentException("At least one enabled mapping rule is required");
		}
		EntityRelationshipConfiguration.validateRuleEntities(
				mapping.getConfiguration(),
				migrationMappingRuleRepository.findByMapping_IdOrderBySequenceNoAsc(mapping.getId()).stream()
						.map(MigrationMappingRule::getEntityName)
						.toList());

		List<MigrationSource> sources = migrationSourceRepository
				.findByMigrationDefinition_IdAndEnabledTrueOrderByCreatedAtAsc(definition.getId());
		if (sources.isEmpty()) {
			throw new IllegalArgumentException("No enabled migration source is configured");
		}
		if (sources.size() > 1) {
			throw new InvalidSourceConfigurationException(
					"Migration definition has multiple enabled sources; specify sourceId");
		}
		Optional<SourceLayout> layout = SourceLayoutConfiguration.parseIfDeclared(sources.get(0).getConfiguration());
		if (layout.isPresent() && layout.get().type() == SourceLayoutType.MULTI_FILE) {
			SourceLayoutConfiguration.validateEntities(layout.get(), mapping.getConfiguration());
			return;
		}
		sourceConnectorRegistry.require(sources.get(0));
	}

	private void deactivateOtherActiveVersions(MigrationDefinition definition) {
		List<MigrationDefinition> others = migrationDefinitionRepository.findOtherActiveByNameForUpdate(
				definition.getName(),
				MigrationConfigStatus.ACTIVE,
				definition.getId());
		Instant now = Instant.now();
		for (MigrationDefinition other : others) {
			other.setStatus(MigrationConfigStatus.INACTIVE);
			other.setUpdatedAt(now);
			other.setUpdatedBy(AuditActor.current());
		}
	}

	private MigrationMapping resolveMapping(UUID mappingId) {
		if (mappingId == null) {
			return null;
		}
		return migrationMappingRepository.findById(mappingId)
				.orElseThrow(() -> new InvalidMappingReferenceException(mappingId));
	}

	private Map<String, Object> copyConfiguration(Map<String, Object> configuration) {
		if (configuration == null) {
			return new HashMap<>();
		}
		return new HashMap<>(configuration);
	}

	private MigrationDefinitionResponse toResponse(MigrationDefinition definition) {
		UUID mappingId = definition.getMapping() == null ? null : definition.getMapping().getId();
		return new MigrationDefinitionResponse(
				definition.getId(),
				definition.getName(),
				definition.getVersion(),
				definition.getDescription(),
				definition.getSourceType(),
				definition.getTargetType(),
				definition.getTargetEntity(),
				mappingId,
				definition.getBatchSize(),
				definition.getLoadMode(),
				definition.getStatus(),
				definition.getConfiguration(),
				definition.getCreatedAt(),
				definition.getCreatedBy(),
				definition.getUpdatedAt(),
				definition.getUpdatedBy());
	}

	private boolean isUniqueNameVersionViolation(DataIntegrityViolationException ex) {
		Throwable cause = ex;
		while (cause != null) {
			if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
				return true;
			}
			String message = cause.getMessage();
			if (message != null && message.contains("uk_migration_definition_name_version")) {
				return true;
			}
			cause = cause.getCause();
		}
		return false;
	}
}
