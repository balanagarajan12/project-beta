package com.etp.export;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class EtpExportApplication {

    public static void main(String[] args) {
        SpringApplication.run(EtpExportApplication.class, args);
    }
}
