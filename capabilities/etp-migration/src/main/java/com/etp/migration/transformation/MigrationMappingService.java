package com.etp.migration.transformation;

import com.etp.migration.entity.MigrationConfigStatus;
import com.etp.migration.entity.MigrationMapping;
import com.etp.migration.entity.MigrationMappingRule;
import com.etp.migration.migration.ControlPlaneSupport;
import com.etp.migration.migration.DuplicateMigrationMappingException;
import com.etp.migration.migration.DuplicateMappingRuleException;
import com.etp.migration.migration.MappingNotFoundException;
import com.etp.migration.migration.dto.PagedResponse;
import com.etp.migration.repository.MigrationMappingRepository;
import com.etp.migration.repository.MigrationMappingRuleRepository;
import com.etp.migration.transformation.dto.CreateMappingRuleRequest;
import com.etp.migration.transformation.dto.CreateMigrationMappingRequest;
import com.etp.migration.transformation.dto.MigrationMappingResponse;
import com.etp.migration.transformation.dto.MigrationMappingRuleResponse;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MigrationMappingService {

	private final MigrationMappingRepository migrationMappingRepository;
	private final MigrationMappingRuleRepository migrationMappingRuleRepository;

	public MigrationMappingService(
			MigrationMappingRepository migrationMappingRepository,
			MigrationMappingRuleRepository migrationMappingRuleRepository) {
		this.migrationMappingRepository = migrationMappingRepository;
		this.migrationMappingRuleRepository = migrationMappingRuleRepository;
	}

	@Transactional
	public MigrationMappingResponse create(CreateMigrationMappingRequest request) {
		if (migrationMappingRepository.existsByNameAndVersion(request.name(), request.version())) {
			throw new DuplicateMigrationMappingException(request.name(), request.version());
		}

		Instant now = Instant.now();
		MigrationMapping mapping = new MigrationMapping();
		mapping.setId(UUID.randomUUID());
		mapping.setName(request.name());
		mapping.setVersion(request.version());
		mapping.setSourceType(request.sourceType());
		mapping.setTargetType(request.targetType());
		mapping.setDescription(request.description());
		mapping.setConfiguration(ControlPlaneSupport.copyConfiguration(request.configuration()));
		mapping.setStatus(MigrationConfigStatus.DRAFT);
		mapping.setCreatedAt(now);
		mapping.setUpdatedAt(now);
		mapping.setCreatedBy(ControlPlaneSupport.SYSTEM_ACTOR);
		mapping.setUpdatedBy(ControlPlaneSupport.SYSTEM_ACTOR);

		try {
			return toMappingResponse(migrationMappingRepository.save(mapping));
		} catch (DataIntegrityViolationException ex) {
			if (ControlPlaneSupport.isUniqueViolation(ex, "uk_migration_mapping_name_version")) {
				throw new DuplicateMigrationMappingException(request.name(), request.version());
			}
			throw ex;
		}
	}

	@Transactional(readOnly = true)
	public MigrationMappingResponse getById(UUID id) {
		return migrationMappingRepository.findById(id)
				.map(this::toMappingResponse)
				.orElseThrow(() -> new MappingNotFoundException(id));
	}

	@Transactional(readOnly = true)
	public PagedResponse<MigrationMappingResponse> list(int page, int size) {
		Page<MigrationMapping> result = migrationMappingRepository.findAll(
				ControlPlaneSupport.pageRequest(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
		return new PagedResponse<>(
				result.getContent().stream().map(this::toMappingResponse).toList(),
				result.getNumber(),
				result.getSize(),
				result.getTotalElements(),
				result.getTotalPages());
	}

	@Transactional
	public MigrationMappingRuleResponse createRule(UUID mappingId, CreateMappingRuleRequest request) {
		MigrationMapping mapping = migrationMappingRepository.findById(mappingId)
				.orElseThrow(() -> new MappingNotFoundException(mappingId));

		if (migrationMappingRuleRepository.existsByMapping_IdAndSequenceNo(mappingId, request.sequenceNo())) {
			throw new DuplicateMappingRuleException(request.sequenceNo());
		}

		Instant now = Instant.now();
		MigrationMappingRule rule = new MigrationMappingRule();
		rule.setMapping(mapping);
		rule.setSequenceNo(request.sequenceNo());
		rule.setSourceField(request.sourceField());
		rule.setTargetField(request.targetField());
		rule.setTransformationType(request.transformationType());
		rule.setTransformationExpression(request.transformationExpression());
		rule.setDefaultValue(request.defaultValue());
		rule.setConfiguration(ControlPlaneSupport.copyConfiguration(request.configuration()));
		rule.setMandatory(Boolean.TRUE.equals(request.mandatory()));
		rule.setEnabled(request.enabled() == null || request.enabled());
		rule.setCreatedAt(now);
		rule.setUpdatedAt(now);

		try {
			return toRuleResponse(migrationMappingRuleRepository.save(rule));
		} catch (DataIntegrityViolationException ex) {
			if (ControlPlaneSupport.isUniqueViolation(ex, "uk_mapping_rule_sequence")) {
				throw new DuplicateMappingRuleException(request.sequenceNo());
			}
			throw ex;
		}
	}

	@Transactional(readOnly = true)
	public PagedResponse<MigrationMappingRuleResponse> listRules(UUID mappingId, int page, int size) {
		if (!migrationMappingRepository.existsById(mappingId)) {
			throw new MappingNotFoundException(mappingId);
		}
		Page<MigrationMappingRule> result = migrationMappingRuleRepository.findByMapping_Id(
				mappingId,
				ControlPlaneSupport.pageRequest(page, size, Sort.by(Sort.Direction.ASC, "sequenceNo")));
		return new PagedResponse<>(
				result.getContent().stream().map(this::toRuleResponse).toList(),
				result.getNumber(),
				result.getSize(),
				result.getTotalElements(),
				result.getTotalPages());
	}

	private MigrationMappingResponse toMappingResponse(MigrationMapping mapping) {
		return new MigrationMappingResponse(
				mapping.getId(),
				mapping.getName(),
				mapping.getVersion(),
				mapping.getSourceType(),
				mapping.getTargetType(),
				mapping.getDescription(),
				mapping.getConfiguration(),
				mapping.getStatus(),
				mapping.getCreatedAt(),
				mapping.getCreatedBy(),
				mapping.getUpdatedAt(),
				mapping.getUpdatedBy());
	}

	private MigrationMappingRuleResponse toRuleResponse(MigrationMappingRule rule) {
		return new MigrationMappingRuleResponse(
				rule.getId(),
				rule.getMapping().getId(),
				rule.getSequenceNo(),
				rule.getSourceField(),
				rule.getTargetField(),
				rule.getTransformationType(),
				rule.getTransformationExpression(),
				rule.getDefaultValue(),
				rule.getConfiguration(),
				rule.isMandatory(),
				rule.isEnabled(),
				rule.getCreatedAt(),
				rule.getUpdatedAt());
	}
}
