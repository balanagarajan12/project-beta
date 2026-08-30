package com.etp.iam;

import com.etp.iam.environment.DatabaseConnectivityHealthIndicator;
import com.etp.iam.environment.KeycloakRealmHealthIndicator;
import com.etp.iam.repository.GroupRoleMappingRepository;
import com.etp.iam.repository.GroupTenantAssignmentRepository;
import com.etp.iam.repository.PrincipalGroupMappingRepository;
import com.etp.iam.repository.PrincipalRepository;
import com.etp.iam.repository.RoleApplicationPermissionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
		"spring.autoconfigure.exclude="
				+ "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
				+ "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
				+ "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
		"keycloak.login-client-id=test-login-client",
		"keycloak.login-client-secret=test-login-secret",
		"etp.iam.cors.allowed-origins=http://localhost:5173"
})
class IamServiceApplicationTests {

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

	@Test
	void contextLoads() {
	}

}
