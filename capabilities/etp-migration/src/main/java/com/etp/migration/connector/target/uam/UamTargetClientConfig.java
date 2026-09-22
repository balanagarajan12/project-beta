package com.etp.migration.connector.target.uam;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(UamTargetProperties.class)
public class UamTargetClientConfig {
}
