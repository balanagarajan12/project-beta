package com.etp.platform.capability.api;

import com.etp.platform.capability.api.dto.CapabilityDetailResponse;
import com.etp.platform.capability.api.dto.CapabilitySummaryResponse;
import com.etp.platform.capability.api.dto.CreateCapabilityRequest;
import com.etp.platform.capability.api.dto.PatchCapabilityRequest;
import com.etp.platform.capability.api.dto.UpdateCapabilityRequest;
import com.etp.platform.capability.service.CapabilityMapper;
import com.etp.platform.capability.service.CapabilityService;
import com.etp.platform.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/capabilities")
@Tag(name = "Capability Management", description = "ETP Capability Registry V1 APIs")
public class CapabilityController {

    private final CapabilityService capabilityService;
    private final CapabilityMapper capabilityMapper;

    public CapabilityController(CapabilityService capabilityService, CapabilityMapper capabilityMapper) {
        this.capabilityService = capabilityService;
        this.capabilityMapper = capabilityMapper;
    }

    @Operation(
            summary = "Create capability",
            description = "Creates a complete capability aggregate with its first version, contract, optional implementation, and dependencies")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "Capability created",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation failure or invalid contract",
                    content = @Content(schema = @Schema(implementation = com.etp.platform.common.api.ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "Duplicate capability code",
                    content = @Content(schema = @Schema(implementation = com.etp.platform.common.api.ErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<ApiResponse<CapabilityDetailResponse>> createCapability(
            @Valid @RequestBody CreateCapabilityRequest request) {
        var capability = capabilityService.createCapability(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.of(capabilityMapper.toDetail(capability)));
    }

    @Operation(summary = "List capabilities", description = "Returns lightweight capability summaries without full contract documents")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Capabilities listed",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @GetMapping
    public ApiResponse<List<CapabilitySummaryResponse>> listCapabilities(
            @Parameter(description = "Filter by category") @RequestParam(required = false) String category,
            @Parameter(description = "Filter by lifecycle status") @RequestParam(required = false) String status) {
        List<CapabilitySummaryResponse> summaries = capabilityService.listCapabilities(category, status).stream()
                .map(capabilityMapper::toSummary)
                .toList();
        return ApiResponse.of(summaries);
    }

    @Operation(summary = "Retrieve capability", description = "Returns the complete capability aggregate including versions, contracts, implementations, and dependencies")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Capability retrieved",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Capability not found",
                    content = @Content(schema = @Schema(implementation = com.etp.platform.common.api.ErrorResponse.class)))
    })
    @GetMapping("/{id}")
    public ApiResponse<CapabilityDetailResponse> getCapability(
            @Parameter(description = "Capability UUID") @PathVariable("id") UUID capabilityId) {
        var capability = capabilityService.getCapability(capabilityId);
        return ApiResponse.of(capabilityMapper.toDetail(capability));
    }

    @Operation(
            summary = "Update capability metadata",
            description = "Replaces capability-level metadata (name, description, category, status). Code is immutable.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Capability updated",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation failure",
                    content = @Content(schema = @Schema(implementation = com.etp.platform.common.api.ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Capability not found",
                    content = @Content(schema = @Schema(implementation = com.etp.platform.common.api.ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "Invalid lifecycle transition",
                    content = @Content(schema = @Schema(implementation = com.etp.platform.common.api.ErrorResponse.class)))
    })
    @PutMapping("/{id}")
    public ApiResponse<CapabilityDetailResponse> updateCapability(
            @Parameter(description = "Capability UUID") @PathVariable("id") UUID capabilityId,
            @Valid @RequestBody UpdateCapabilityRequest request) {
        var capability = capabilityService.updateCapability(capabilityId, request);
        return ApiResponse.of(capabilityMapper.toDetail(capability));
    }

    @Operation(
            summary = "Modify capability",
            description = "Updates capability metadata, lifecycle status, creates a new version, or transitions version lifecycle")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Capability updated",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation failure",
                    content = @Content(schema = @Schema(implementation = com.etp.platform.common.api.ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Capability or version not found",
                    content = @Content(schema = @Schema(implementation = com.etp.platform.common.api.ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "Conflict — duplicate version or invalid lifecycle transition",
                    content = @Content(schema = @Schema(implementation = com.etp.platform.common.api.ErrorResponse.class)))
    })
    @PatchMapping("/{id}")
    public ApiResponse<CapabilityDetailResponse> patchCapability(
            @Parameter(description = "Capability UUID") @PathVariable("id") UUID capabilityId,
            @Valid @RequestBody PatchCapabilityRequest request) {
        var capability = capabilityService.patchCapability(capabilityId, request);
        return ApiResponse.of(capabilityMapper.toDetail(capability));
    }
}
