package com.etp.migration.connector.source.sftp;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SftpCredentialProperties.class)
public class SftpSourceConfig {
}
