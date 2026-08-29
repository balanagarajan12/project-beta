package com.etp.platform.security;

import com.etp.platform.AbstractPostgreSqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CorsIntegrationTest extends AbstractPostgreSqlIntegrationTest {

  private static final String LOCAL_UI_ORIGIN = "http://localhost:5173";

  @Autowired
  private MockMvc mockMvc;

  @Test
  void preflightForCapabilitiesAllowsLocalUiOrigin() throws Exception {
    mockMvc.perform(options("/api/v1/capabilities")
                    .header("Origin", LOCAL_UI_ORIGIN)
                    .header("Access-Control-Request-Method", "GET")
                    .header("Access-Control-Request-Headers", "authorization,content-type"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", LOCAL_UI_ORIGIN))
            .andExpect(header().string("Access-Control-Allow-Methods", "GET,POST,PUT,PATCH,OPTIONS"));
  }

  @Test
  void getCapabilitiesIncludesCorsHeadersForLocalUiOrigin() throws Exception {
    mockMvc.perform(get("/api/v1/capabilities").header("Origin", LOCAL_UI_ORIGIN))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", LOCAL_UI_ORIGIN));
  }
}
