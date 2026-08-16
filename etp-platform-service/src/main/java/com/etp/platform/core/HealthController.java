package com.etp.platform.core;

import com.etp.platform.common.api.ApiResponse;
import com.etp.platform.core.dto.HealthResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    @Value("${spring.application.name}")
    private String serviceName;

    @GetMapping
    public ApiResponse<HealthResponse> health() {
        return ApiResponse.of(new HealthResponse("UP", serviceName));
    }
}
