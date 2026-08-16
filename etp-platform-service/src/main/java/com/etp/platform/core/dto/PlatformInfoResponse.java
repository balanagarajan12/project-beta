package com.etp.platform.core.dto;

public record PlatformInfoResponse(
        String name,
        String version,
        String description
) {
}
