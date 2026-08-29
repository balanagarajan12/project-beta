package com.etp.platform.capability.service;

import com.etp.platform.capability.Capability;
import com.etp.platform.capability.CapabilityContract;
import com.etp.platform.capability.CapabilityDependency;
import com.etp.platform.capability.CapabilityImplementation;
import com.etp.platform.capability.CapabilityVersion;
import com.etp.platform.capability.api.dto.ContractRequest;
import com.etp.platform.capability.api.dto.CreateCapabilityRequest;
import com.etp.platform.capability.api.dto.DependencyRequest;
import com.etp.platform.capability.api.dto.ImplementationRequest;
import com.etp.platform.capability.api.dto.NewVersionRequest;
import com.etp.platform.capability.api.dto.PatchCapabilityRequest;
import com.etp.platform.capability.api.dto.UpdateCapabilityRequest;
import com.etp.platform.capability.api.dto.VersionRequest;
import com.etp.platform.capability.domain.CapabilityLifecycleStatus;
import com.etp.platform.capability.domain.CapabilityVersionLifecycleStatus;
import com.etp.platform.capability.domain.LifecycleTransitionValidator;
import com.etp.platform.capability.domain.SemanticVersionComparator;
import com.etp.platform.capability.repository.CapabilityContractRepository;
import com.etp.platform.capability.repository.CapabilityDependencyRepository;
import com.etp.platform.capability.repository.CapabilityImplementationRepository;
import com.etp.platform.capability.repository.CapabilityRepository;
import com.etp.platform.capability.repository.CapabilityVersionRepository;
import com.etp.platform.capability.validation.CapabilityContractDocumentValidator;
import com.etp.platform.common.audit.AuditService;
import com.etp.platform.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class CapabilityService {

    private final CapabilityRepository capabilityRepository;
    private final CapabilityVersionRepository capabilityVersionRepository;
    private final CapabilityContractRepository capabilityContractRepository;
    private final CapabilityImplementationRepository capabilityImplementationRepository;
    private final CapabilityDependencyRepository capabilityDependencyRepository;
    private final CapabilityContractDocumentValidator contractDocumentValidator;
    private final CapabilityMapper capabilityMapper;
    private final AuditService auditService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CapabilityService(
            CapabilityRepository capabilityRepository,
            CapabilityVersionRepository capabilityVersionRepository,
            CapabilityContractRepository capabilityContractRepository,
            CapabilityImplementationRepository capabilityImplementationRepository,
            CapabilityDependencyRepository capabilityDependencyRepository,
            CapabilityContractDocumentValidator contractDocumentValidator,
            CapabilityMapper capabilityMapper,
            AuditService auditService) {
        this.capabilityRepository = capabilityRepository;
        this.capabilityVersionRepository = capabilityVersionRepository;
        this.capabilityContractRepository = capabilityContractRepository;
        this.capabilityImplementationRepository = capabilityImplementationRepository;
        this.capabilityDependencyRepository = capabilityDependencyRepository;
        this.contractDocumentValidator = contractDocumentValidator;
        this.capabilityMapper = capabilityMapper;
        this.auditService = auditService;
    }

    @Transactional
    public Capability createCapability(CreateCapabilityRequest request) {
        if (capabilityRepository.existsByCapabilityCode(request.getCode())) {
            throw BusinessException.conflict("DUPLICATE_CAPABILITY_CODE", "Capability code already exists: " + request.getCode());
        }

        validateContractRequest(request.getContract());
        contractDocumentValidator.validate(
                request.getCode(), toContractJsonNode(request.getContract().getDocument()));

        Instant now = Instant.now();
        UUID capabilityId = UUID.randomUUID();

        Capability capability = new Capability();
        capability.setCapabilityId(capabilityId);
        capability.setCapabilityCode(request.getCode());
        capability.setCapabilityName(request.getName());
        capability.setDescription(request.getDescription());
        capability.setCategory(request.getCategory());
        capability.setLifecycleStatus(CapabilityLifecycleStatus.DRAFT.name());
        capability.setCreatedAt(now);
        capability.setCreatedBy(AuditService.PERFORMED_BY_SYSTEM);
        capability.setUpdatedAt(now);
        capability.setUpdatedBy(AuditService.PERFORMED_BY_SYSTEM);
        capabilityRepository.save(capability);

        CapabilityVersion version = createVersionAggregate(
                capability,
                request.getVersion(),
                request.getContract(),
                request.getImplementation(),
                request.getDependencies(),
                now);

        auditService.record(
                AuditService.ENTITY_CAPABILITY,
                capabilityId,
                AuditService.ACTION_CREATE,
                null,
                Map.of(
                        "code", request.getCode(),
                        "name", request.getName(),
                        "initialVersion", request.getVersion().getVersion(),
                        "versionId", version.getCapabilityVersionId().toString()));

        return capability;
    }

    @Transactional(readOnly = true)
    public List<Capability> listCapabilities(String category, String status) {
        return capabilityRepository.findByOptionalFilters(category, status);
    }

    @Transactional(readOnly = true)
    public Capability getCapability(UUID capabilityId) {
        return capabilityRepository.findById(capabilityId)
                .orElseThrow(() -> BusinessException.notFound("CAPABILITY_NOT_FOUND", "Capability not found: " + capabilityId));
    }

    @Transactional
    public Capability updateCapability(UUID capabilityId, UpdateCapabilityRequest request) {
        Capability capability = getCapability(capabilityId);
        Instant now = Instant.now();
        Map<String, Object> oldMetadata = new HashMap<>();
        Map<String, Object> newMetadata = new HashMap<>();

        oldMetadata.put("name", capability.getCapabilityName());
        capability.setCapabilityName(request.getName());
        newMetadata.put("name", request.getName());

        oldMetadata.put("description", capability.getDescription());
        capability.setDescription(request.getDescription());
        newMetadata.put("description", request.getDescription());

        oldMetadata.put("category", capability.getCategory());
        capability.setCategory(request.getCategory());
        newMetadata.put("category", request.getCategory());

        String oldStatus = capability.getLifecycleStatus();
        if (!oldStatus.equals(request.getStatus())) {
            LifecycleTransitionValidator.validateCapabilityTransition(oldStatus, request.getStatus());
            capability.setLifecycleStatus(request.getStatus());
            auditService.record(
                    AuditService.ENTITY_CAPABILITY,
                    capabilityId,
                    AuditService.ACTION_STATUS_CHANGE,
                    Map.of("status", oldStatus),
                    Map.of("status", request.getStatus()));
        }

        capability.setUpdatedAt(now);
        capability.setUpdatedBy(AuditService.PERFORMED_BY_SYSTEM);
        auditService.record(
                AuditService.ENTITY_CAPABILITY,
                capabilityId,
                AuditService.ACTION_METADATA_UPDATE,
                oldMetadata,
                newMetadata);

        capabilityRepository.save(capability);
        return capability;
    }

    @Transactional
    public Capability patchCapability(UUID capabilityId, PatchCapabilityRequest request) {
        Capability capability = getCapability(capabilityId);
        Instant now = Instant.now();
        boolean metadataChanged = false;
        Map<String, Object> oldMetadata = new HashMap<>();
        Map<String, Object> newMetadata = new HashMap<>();

        if (request.getName() != null) {
            oldMetadata.put("name", capability.getCapabilityName());
            capability.setCapabilityName(request.getName());
            newMetadata.put("name", request.getName());
            metadataChanged = true;
        }
        if (request.getDescription() != null) {
            oldMetadata.put("description", capability.getDescription());
            capability.setDescription(request.getDescription());
            newMetadata.put("description", request.getDescription());
            metadataChanged = true;
        }
        if (request.getStatus() != null) {
            String oldStatus = capability.getLifecycleStatus();
            LifecycleTransitionValidator.validateCapabilityTransition(oldStatus, request.getStatus());
            capability.setLifecycleStatus(request.getStatus());
            auditService.record(
                    AuditService.ENTITY_CAPABILITY,
                    capabilityId,
                    AuditService.ACTION_STATUS_CHANGE,
                    Map.of("status", oldStatus),
                    Map.of("status", request.getStatus()));
        }

        if (metadataChanged) {
            capability.setUpdatedAt(now);
            capability.setUpdatedBy(AuditService.PERFORMED_BY_SYSTEM);
            auditService.record(
                    AuditService.ENTITY_CAPABILITY,
                    capabilityId,
                    AuditService.ACTION_METADATA_UPDATE,
                    oldMetadata,
                    newMetadata);
        }

        if (request.getNewVersion() != null) {
            createNewVersion(capability, request.getNewVersion(), now);
        }

        if (request.getVersionStatus() != null) {
            updateVersionStatus(capability, request.getVersionStatus().getVersionId(), request.getVersionStatus().getStatus(), now);
        }

        capabilityRepository.save(capability);
        return capability;
    }

    private void createNewVersion(Capability capability, NewVersionRequest newVersionRequest, Instant now) {
        if (capabilityVersionRepository.existsByCapabilityCapabilityIdAndVersion(
                capability.getCapabilityId(), newVersionRequest.getVersion())) {
            throw BusinessException.conflict(
                    "DUPLICATE_CAPABILITY_VERSION",
                    "Capability version already exists: " + newVersionRequest.getVersion());
        }

        validateContractRequest(newVersionRequest.getContract());
        contractDocumentValidator.validate(
                capability.getCapabilityCode(), toContractJsonNode(newVersionRequest.getContract().getDocument()));

        CapabilityVersion version = createVersionAggregate(
                capability,
                newVersionRequest,
                newVersionRequest.getContract(),
                newVersionRequest.getImplementation(),
                newVersionRequest.getDependencies(),
                now);

        auditService.record(
                AuditService.ENTITY_CAPABILITY_VERSION,
                version.getCapabilityVersionId(),
                AuditService.ACTION_CREATE_VERSION,
                null,
                Map.of(
                        "capabilityId", capability.getCapabilityId().toString(),
                        "version", newVersionRequest.getVersion(),
                        "status", CapabilityVersionLifecycleStatus.DRAFT.name()));
    }

    private void updateVersionStatus(Capability capability, UUID versionId, String targetStatus, Instant now) {
        CapabilityVersion version = capabilityVersionRepository.findById(versionId)
                .orElseThrow(() -> BusinessException.notFound("CAPABILITY_VERSION_NOT_FOUND", "Capability version not found: " + versionId));

        if (!version.getCapability().getCapabilityId().equals(capability.getCapabilityId())) {
            throw BusinessException.conflict(
                    "CAPABILITY_VERSION_MISMATCH",
                    "Capability version does not belong to capability: " + capability.getCapabilityId());
        }

        CapabilityVersionLifecycleStatus target = CapabilityVersionLifecycleStatus.valueOf(targetStatus);
        CapabilityVersionLifecycleStatus current = CapabilityVersionLifecycleStatus.valueOf(version.getStatus());

        if (target == CapabilityVersionLifecycleStatus.ACTIVE) {
            activateVersion(capability, version, now);
            return;
        }

        if (!current.canTransitionTo(target)) {
            throw BusinessException.conflict(
                    "INVALID_LIFECYCLE_TRANSITION",
                    "Capability version lifecycle transition from " + current + " to " + target + " is not allowed");
        }

        if (current.isImmutableContent() && target != current) {
            if (target != CapabilityVersionLifecycleStatus.DEPRECATED
                    && target != CapabilityVersionLifecycleStatus.RETIRED) {
                throw BusinessException.conflict(
                        "ACTIVE_VERSION_IMMUTABLE",
                        "Active capability version content cannot be modified; create a new version instead");
            }
        }

        applyVersionStatusChange(version, target, now);
    }

    private void activateVersion(Capability capability, CapabilityVersion version, Instant now) {
        CapabilityVersionLifecycleStatus current = CapabilityVersionLifecycleStatus.valueOf(version.getStatus());
        if (!current.canTransitionTo(CapabilityVersionLifecycleStatus.ACTIVE)) {
            throw BusinessException.conflict(
                    "INVALID_LIFECYCLE_TRANSITION",
                    "Capability version lifecycle transition from " + current + " to ACTIVE is not allowed");
        }

        Optional<CapabilityVersion> currentActive = capabilityVersionRepository
                .findByCapabilityCapabilityIdAndStatus(
                        capability.getCapabilityId(), CapabilityVersionLifecycleStatus.ACTIVE.name());

        if (currentActive.isPresent()
                && !currentActive.get().getCapabilityVersionId().equals(version.getCapabilityVersionId())) {
            CapabilityVersion previousActive = currentActive.get();
            if (SemanticVersionComparator.compare(version.getVersion(), previousActive.getVersion()) <= 0) {
                throw BusinessException.conflict(
                        "INVALID_LIFECYCLE_TRANSITION",
                        "Cannot activate version " + version.getVersion()
                                + " because it is not higher than the current active version "
                                + previousActive.getVersion());
            }
            applyVersionStatusChange(previousActive, CapabilityVersionLifecycleStatus.DEPRECATED, now);
            capabilityVersionRepository.flush();
        }

        boolean firstVersionActivation = currentActive.isEmpty()
                && CapabilityLifecycleStatus.DRAFT.name().equals(capability.getLifecycleStatus());

        applyVersionStatusChange(version, CapabilityVersionLifecycleStatus.ACTIVE, now);

        if (firstVersionActivation) {
            String oldCapabilityStatus = capability.getLifecycleStatus();
            capability.setLifecycleStatus(CapabilityLifecycleStatus.ACTIVE.name());
            capability.setUpdatedAt(now);
            capability.setUpdatedBy(AuditService.PERFORMED_BY_SYSTEM);
            auditService.record(
                    AuditService.ENTITY_CAPABILITY,
                    capability.getCapabilityId(),
                    AuditService.ACTION_STATUS_CHANGE,
                    Map.of("status", oldCapabilityStatus),
                    Map.of("status", CapabilityLifecycleStatus.ACTIVE.name()));
        }
    }

    private void applyVersionStatusChange(
            CapabilityVersion version,
            CapabilityVersionLifecycleStatus targetStatus,
            Instant now) {
        String oldStatus = version.getStatus();
        if (oldStatus.equals(targetStatus.name())) {
            return;
        }

        LifecycleTransitionValidator.validateVersionTransition(oldStatus, targetStatus.name());
        version.setStatus(targetStatus.name());

        if (targetStatus == CapabilityVersionLifecycleStatus.ACTIVE && version.getEffectiveFrom() == null) {
            version.setEffectiveFrom(now);
        }
        if (targetStatus == CapabilityVersionLifecycleStatus.DEPRECATED && version.getDeprecatedAt() == null) {
            version.setDeprecatedAt(now);
        }

        capabilityVersionRepository.save(version);

        auditService.record(
                AuditService.ENTITY_CAPABILITY_VERSION,
                version.getCapabilityVersionId(),
                AuditService.ACTION_STATUS_CHANGE,
                Map.of("status", oldStatus),
                Map.of("status", targetStatus.name()));
    }

    private CapabilityVersion createVersionAggregate(
            Capability capability,
            VersionRequest versionRequest,
            ContractRequest contractRequest,
            ImplementationRequest implementationRequest,
            List<DependencyRequest> dependencies,
            Instant now) {
        CapabilityVersion version = new CapabilityVersion();
        UUID versionId = UUID.randomUUID();
        version.setCapabilityVersionId(versionId);
        version.setCapability(capability);
        version.setVersion(versionRequest.getVersion());
        version.setStatus(CapabilityVersionLifecycleStatus.DRAFT.name());
        version.setReleaseNotes(versionRequest.getDescription());
        version.setContractVersion(contractRequest.getContractVersion());
        version.setCreatedAt(now);
        version.setCreatedBy(AuditService.PERFORMED_BY_SYSTEM);
        capabilityVersionRepository.save(version);

        persistContract(version, contractRequest, now);
        if (implementationRequest != null) {
            persistImplementation(version, implementationRequest, now);
        }
        if (dependencies != null && !dependencies.isEmpty()) {
            persistDependencies(version, dependencies, now);
        }

        return version;
    }

    private void persistContract(CapabilityVersion version, ContractRequest contractRequest, Instant now) {
        CapabilityContract contract = new CapabilityContract();
        contract.setContractId(UUID.randomUUID());
        contract.setCapabilityVersion(version);
        contract.setContractType(contractRequest.getContractType());
        contract.setContractVersion(contractRequest.getContractVersion());
        contract.setContractFormat(contractRequest.getContractFormat());
        contract.setContractDocument(toContractJsonNode(contractRequest.getDocument()));
        contract.setContractLocation(contractRequest.getLocation());
        contract.setStatus("ACTIVE");
        contract.setCreatedAt(now);
        contract.setUpdatedAt(now);
        capabilityContractRepository.save(contract);
    }

    private void persistImplementation(CapabilityVersion version, ImplementationRequest request, Instant now) {
        CapabilityImplementation implementation = new CapabilityImplementation();
        implementation.setImplementationId(UUID.randomUUID());
        implementation.setCapabilityVersion(version);
        implementation.setImplementationCode(request.getCode());
        implementation.setImplementationName(request.getName());
        implementation.setTechnologyName(request.getTechnology());
        implementation.setTechnologyVersion(request.getTechnologyVersion());
        implementation.setStatus("ACTIVE");
        implementation.setCreatedAt(now);
        capabilityImplementationRepository.save(implementation);
    }

    private void persistDependencies(CapabilityVersion version, List<DependencyRequest> dependencies, Instant now) {
        for (DependencyRequest dependencyRequest : dependencies) {
            UUID dependsOnId = dependencyRequest.getCapabilityVersionId();
            if (dependsOnId.equals(version.getCapabilityVersionId())) {
                throw new BusinessException("SELF_DEPENDENCY", "Capability version cannot depend on itself");
            }

            CapabilityVersion dependsOn = capabilityVersionRepository.findById(dependsOnId)
                    .orElseThrow(() -> BusinessException.notFound(
                            "DEPENDENCY_NOT_FOUND",
                            "Dependency capability version not found: " + dependsOnId));

            CapabilityDependency dependency = new CapabilityDependency();
            dependency.setDependencyId(UUID.randomUUID());
            dependency.setCapabilityVersion(version);
            dependency.setDependsOnCapabilityVersion(dependsOn);
            dependency.setDependencyType(dependencyRequest.getDependencyType());
            dependency.setMandatory(dependencyRequest.getMandatory());
            dependency.setCreatedAt(now);
            capabilityDependencyRepository.save(dependency);
        }
    }

    private void validateContractRequest(ContractRequest contractRequest) {
        if (!contractRequest.hasInlineDocument() && !contractRequest.hasExternalLocation()) {
            throw new BusinessException(
                    "INVALID_CAPABILITY_CONTRACT",
                    "Contract must include either an inline document or an external location");
        }
    }

    private JsonNode toContractJsonNode(Object document) {
        if (document == null) {
            return null;
        }
        return objectMapper.valueToTree(document);
    }
}
