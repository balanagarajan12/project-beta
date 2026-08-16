package com.etp.platform;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("local")
class LocalDevelopmentDatabaseConfigurationTest {

    @Test
    void contextLoadsWithLocalH2Database() {
    }
}
