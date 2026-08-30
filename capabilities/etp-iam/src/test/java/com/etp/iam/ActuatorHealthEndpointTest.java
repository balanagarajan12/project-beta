package com.etp.iam;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.etp.iam.environment.DatabaseConnectivityHealthIndicator;
import com.etp.iam.environment.KeycloakRealmHealthIndicator;
import com.etp.iam.repository.GroupRoleMappingRepository;
import com.etp.iam.repository.GroupTenantAssignmentRepository;
import com.etp.iam.repository.PrincipalGroupMappingRepository;
import com.etp.iam.repository.PrincipalRepository;
import com.etp.iam.repository.RoleApplicationPermissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
		"spring.autoconfigure.exclude="
				+ "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
				+ "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
				+ "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
		"keycloak.login-client-id=test-login-client",
		"keycloak.login-client-secret=test-login-secret",
		"etp.iam.cors.allowed-origins=http://localhost:5173",
		"management.health.db.enabled=false"
})
@AutoConfigureMockMvc
class ActuatorHealthEndpointTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	@MockitoBean
	private PrincipalRepository principalRepository;

	@MockitoBean
	private PrincipalGroupMappingRepository principalGroupMappingRepository;

	@MockitoBean
	private GroupRoleMappingRepository groupRoleMappingRepository;

	@MockitoBean
	private RoleApplicationPermissionRepository roleApplicationPermissionRepository;

	@MockitoBean
	private GroupTenantAssignmentRepository groupTenantAssignmentRepository;

	@MockitoBean
	private DatabaseConnectivityHealthIndicator databaseConnectivityHealthIndicator;

	@MockitoBean
	private KeycloakRealmHealthIndicator keycloakRealmHealthIndicator;

	@BeforeEach
	void stubHealthIndicators() {
		org.mockito.Mockito.when(databaseConnectivityHealthIndicator.health())
				.thenReturn(Health.up().withDetail("database", "connectivity verified").build());
		org.mockito.Mockito.when(keycloakRealmHealthIndicator.health())
				.thenReturn(Health.up().withDetail("keycloak", "realm endpoint reachable").build());
	}

	@Test
	void actuatorHealthIsPublicAndReportsUp() throws Exception {
		mockMvc.perform(get("/actuator/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}
}
