package com.etp.platform.capability.service;

import com.etp.platform.capability.Capability;
import com.etp.platform.capability.CapabilityContract;
import com.etp.platform.capability.CapabilityDependency;
import com.etp.platform.capability.CapabilityImplementation;
import com.etp.platform.capability.CapabilityVersion;
import com.etp.platform.capability.api.dto.CapabilityDetailResponse;
import com.etp.platform.capability.api.dto.CapabilitySummaryResponse;
import com.etp.platform.capability.api.dto.CapabilityVersionDetailResponse;
import com.etp.platform.capability.api.dto.ContractResponse;
import com.etp.platform.capability.api.dto.DependencyResponse;
import com.etp.platform.capability.api.dto.ImplementationResponse;
import com.etp.platform.capability.repository.CapabilityContractRepository;
import com.etp.platform.capability.repository.CapabilityDependencyRepository;
import com.etp.platform.capability.repository.CapabilityImplementationRepository;
import com.etp.platform.capability.repository.CapabilityVersionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class CapabilityMapper {

    private final CapabilityVersionRepository capabilityVersionRepository;
    private final CapabilityContractRepository capabilityContractRepository;
    private final CapabilityImplementationRepository capabilityImplementationRepository;
    private final CapabilityDependencyRepository capabilityDependencyRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CapabilityMapper(
            CapabilityVersionRepository capabilityVersionRepository,
            CapabilityContractRepository capabilityContractRepository,
            CapabilityImplementationRepository capabilityImplementationRepository,
            CapabilityDependencyRepository capabilityDependencyRepository) {
        this.capabilityVersionRepository = capabilityVersionRepository;
        this.capabilityContractRepository = capabilityContractRepository;
        this.capabilityImplementationRepository = capabilityImplementationRepository;
        this.capabilityDependencyRepository = capabilityDependencyRepository;
    }

    public CapabilitySummaryResponse toSummary(Capability capability) {
        CapabilitySummaryResponse response = new CapabilitySummaryResponse();
        response.setId(capability.getCapabilityId());
        response.setCode(capability.getCapabilityCode());
        response.setName(capability.getCapabilityName());
        response.setDescription(capability.getDescription());
        response.setCategory(capability.getCategory());
        response.setStatus(capability.getLifecycleStatus());
        response.setCreatedAt(capability.getCreatedAt());
        response.setUpdatedAt(capability.getUpdatedAt());
        capabilityVersionRepository.findFirstByCapabilityCapabilityIdOrderByCreatedAtDesc(capability.getCapabilityId())
                .ifPresent(version -> response.setVersion(version.getVersion()));
        return response;
    }

    @Transactional(readOnly = true)
    public CapabilityDetailResponse toDetail(Capability capability) {
        CapabilityDetailResponse response = new CapabilityDetailResponse();
        response.setId(capability.getCapabilityId());
        response.setCode(capability.getCapabilityCode());
        response.setName(capability.getCapabilityName());
        response.setDescription(capability.getDescription());
        response.setCategory(capability.getCategory());
        response.setStatus(capability.getLifecycleStatus());
        response.setCreatedAt(capability.getCreatedAt());
        response.setUpdatedAt(capability.getUpdatedAt());

        List<CapabilityVersion> versions =
                capabilityVersionRepository.findByCapabilityCapabilityIdOrderByCreatedAtDesc(capability.getCapabilityId());
        if (!versions.isEmpty()) {
            response.setVersion(versions.getFirst().getVersion());
        }
        response.setVersions(versions.stream().map(this::toVersionDetail).toList());
        return response;
    }

    private CapabilityVersionDetailResponse toVersionDetail(CapabilityVersion version) {
        CapabilityVersionDetailResponse response = new CapabilityVersionDetailResponse();
        response.setId(version.getCapabilityVersionId());
        response.setVersion(version.getVersion());
        response.setStatus(version.getStatus());
        response.setDescription(version.getReleaseNotes());

        List<CapabilityContract> contracts =
                capabilityContractRepository.findByCapabilityVersionCapabilityVersionId(version.getCapabilityVersionId());
        if (!contracts.isEmpty()) {
            response.setContract(toContractResponse(contracts.getFirst()));
        }

        List<CapabilityImplementation> implementations =
                capabilityImplementationRepository.findByCapabilityVersionCapabilityVersionId(version.getCapabilityVersionId());
        if (!implementations.isEmpty()) {
            response.setImplementation(toImplementationResponse(implementations.getFirst()));
        }

        List<CapabilityDependency> dependencies =
                capabilityDependencyRepository.findByCapabilityVersionCapabilityVersionId(version.getCapabilityVersionId());
        response.setDependencies(dependencies.stream().map(this::toDependencyResponse).toList());
        return response;
    }

    private ContractResponse toContractResponse(CapabilityContract contract) {
        ContractResponse response = new ContractResponse();
        response.setContractType(contract.getContractType());
        response.setContractVersion(contract.getContractVersion());
        response.setContractFormat(contract.getContractFormat());
        response.setDocument(toApiDocument(contract.getContractDocument()));
        response.setLocation(contract.getContractLocation());
        return response;
    }

    private Object toApiDocument(JsonNode contractDocument) {
        if (contractDocument == null || contractDocument.isNull()) {
            return null;
        }
        return objectMapper.convertValue(contractDocument, Object.class);
    }

    private ImplementationResponse toImplementationResponse(CapabilityImplementation implementation) {
        ImplementationResponse response = new ImplementationResponse();
        response.setCode(implementation.getImplementationCode());
        response.setName(implementation.getImplementationName());
        response.setTechnology(implementation.getTechnologyName());
        response.setTechnologyVersion(implementation.getTechnologyVersion());
        return response;
    }

    private DependencyResponse toDependencyResponse(CapabilityDependency dependency) {
        DependencyResponse response = new DependencyResponse();
        response.setCapabilityVersionId(dependency.getDependsOnCapabilityVersion().getCapabilityVersionId());
        response.setDependencyType(dependency.getDependencyType());
        response.setMandatory(dependency.isMandatory());
        return response;
    }
}
