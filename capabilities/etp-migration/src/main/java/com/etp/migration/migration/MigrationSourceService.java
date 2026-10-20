package com.etp.migration.migration;

import com.etp.migration.config.AuditActor;
import com.etp.migration.connector.source.csv.SourceLayoutConfiguration;
import com.etp.migration.connector.source.sftp.SftpCsvSourceConfiguration;
import com.etp.migration.entity.MigrationDefinition;
import com.etp.migration.entity.MigrationSource;
import com.etp.migration.migration.dto.CreateMigrationSourceRequest;
import com.etp.migration.migration.dto.MigrationSourceResponse;
import com.etp.migration.migration.dto.PagedResponse;
import com.etp.migration.repository.MigrationDefinitionRepository;
import com.etp.migration.repository.MigrationSourceRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MigrationSourceService {

	private final MigrationDefinitionRepository migrationDefinitionRepository;
	private final MigrationSourceRepository migrationSourceRepository;

	public MigrationSourceService(
			MigrationDefinitionRepository migrationDefinitionRepository,
			MigrationSourceRepository migrationSourceRepository) {
		this.migrationDefinitionRepository = migrationDefinitionRepository;
		this.migrationSourceRepository = migrationSourceRepository;
	}

	@Transactional
	public MigrationSourceResponse create(UUID migrationId, CreateMigrationSourceRequest request) {
		MigrationDefinition definition = migrationDefinitionRepository.findById(migrationId)
				.orElseThrow(() -> new MigrationDefinitionNotFoundException(migrationId));
		if (SftpCsvSourceConfiguration.declaresSftp(request.configuration())) {
			SftpCsvSourceConfiguration.from(request.configuration());
		} else {
			SourceLayoutConfiguration.validateDeclared(
					request.configuration(),
					definition.getMapping() == null ? null : definition.getMapping().getConfiguration());
		}

		Instant now = Instant.now();
		MigrationSource source = new MigrationSource();
		source.setId(UUID.randomUUID());
		source.setMigrationDefinition(definition);
		source.setName(request.name());
		source.setSourceType(request.sourceType());
		source.setConfiguration(ControlPlaneSupport.copyConfiguration(request.configuration()));
		source.setEnabled(request.enabled() == null || request.enabled());
		source.setCreatedAt(now);
		source.setUpdatedAt(now);
		String actor = AuditActor.current();
		source.setCreatedBy(actor);
		source.setUpdatedBy(actor);
		return toResponse(migrationSourceRepository.save(source));
	}

	@Transactional(readOnly = true)
	public PagedResponse<MigrationSourceResponse> list(UUID migrationId, int page, int size) {
		requireDefinition(migrationId);
		Page<MigrationSource> result = migrationSourceRepository.findByMigrationDefinition_Id(
				migrationId,
				ControlPlaneSupport.pageRequest(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
		return new PagedResponse<>(
				result.getContent().stream().map(this::toResponse).toList(),
				result.getNumber(),
				result.getSize(),
				result.getTotalElements(),
				result.getTotalPages());
	}

	@Transactional(readOnly = true)
	public MigrationSourceResponse getById(UUID migrationId, UUID sourceId) {
		requireDefinition(migrationId);
		return migrationSourceRepository.findByIdAndMigrationDefinition_Id(sourceId, migrationId)
				.map(this::toResponse)
				.orElseThrow(() -> new MigrationSourceNotFoundException(sourceId));
	}

	private void requireDefinition(UUID migrationId) {
		if (!migrationDefinitionRepository.existsById(migrationId)) {
			throw new MigrationDefinitionNotFoundException(migrationId);
		}
	}

	private MigrationSourceResponse toResponse(MigrationSource source) {
		return new MigrationSourceResponse(
				source.getId(),
				source.getMigrationDefinition().getId(),
				source.getName(),
				source.getSourceType(),
				source.getConfiguration(),
				source.isEnabled(),
				source.getCreatedAt(),
				source.getCreatedBy(),
				source.getUpdatedAt(),
				source.getUpdatedBy());
	}
}
