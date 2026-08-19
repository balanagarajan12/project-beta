package com.etp.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.etp.iam.repository.GroupRoleMappingRepository;
import com.etp.iam.repository.GroupTenantAssignmentRepository;
import com.etp.iam.repository.PrincipalGroupMappingRepository;
import com.etp.iam.repository.PrincipalRepository;
import com.etp.iam.repository.RoleApplicationPermissionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
		"spring.autoconfigure.exclude="
				+ "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
				+ "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
		"keycloak.login-client-id=test-login-client",
		"keycloak.login-client-secret=test-login-secret",
		"etp.iam.cors.allowed-origins=http://localhost:5173"
})
@AutoConfigureMockMvc
class OpenApiDocumentationTest {

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

	@Test
	void openApiDocumentsAuthenticationEndpoints() throws Exception {
		String openApiJson = mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();

		assertThat(openApiJson).isNotBlank();
		assertThat(openApiJson).contains("/api/v1/auth/login");
		assertThat(openApiJson).contains("/api/v1/auth/token");
		assertThat(openApiJson).contains("Human user authentication");
		assertThat(openApiJson).contains("System-to-system authentication");
		assertThat(openApiJson).contains("\"format\":\"password\"");
		assertThat(openApiJson).doesNotContain("/api/v1/auth/logout");
		assertThat(openApiJson).doesNotContain("/api/v1/auth/refresh");
		assertThat(openApiJson).doesNotContain("/api/v1/auth/me");
	}
}
