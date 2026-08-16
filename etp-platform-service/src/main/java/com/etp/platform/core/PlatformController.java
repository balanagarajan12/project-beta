package com.etp.platform.core;

import com.etp.platform.common.api.ApiResponse;
import com.etp.platform.core.dto.PlatformInfoResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform")
public class PlatformController {

    @Value("${etp.platform.name}")
    private String platformName;

    @Value("${etp.platform.version}")
    private String platformVersion;

    @GetMapping("/info")
    public ApiResponse<PlatformInfoResponse> info() {
        PlatformInfoResponse info = new PlatformInfoResponse(
                platformName,
                platformVersion,
                "ETP Platform Service foundation"
        );
        return ApiResponse.of(info);
    }
}
