package com.etp.iam.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.etp.iam.entity.Group;
import com.etp.iam.entity.GroupTenantAssignment;
import com.etp.iam.entity.GroupType;
import com.etp.iam.entity.Principal;
import com.etp.iam.entity.PrincipalType;
import com.etp.iam.entity.Scope;
import com.etp.iam.entity.Tenant;
import com.etp.iam.repository.GroupRoleMappingRepository;
import com.etp.iam.repository.GroupTenantAssignmentRepository;
import com.etp.iam.repository.PrincipalGroupMappingRepository;
import com.etp.iam.repository.PrincipalRepository;
import com.etp.iam.repository.RoleApplicationPermissionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AuthorizationServiceTest {

	private static final String ISSUER = "http://localhost:8180/realms/etp";
	private static final String SUBJECT = "179ec5bc-3616-4ecb-8300-f40f9053bfa3";
	private static final Instant FIXED_NOW = Instant.parse("2026-08-14T12:00:00Z");

	private static final UUID PRINCIPAL_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID GROUP_ID_1 = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID GROUP_ID_2 = UUID.fromString("33333333-3333-3333-3333-333333333333");
	private static final UUID ROLE_ID_1 = UUID.fromString("44444444-4444-4444-4444-444444444444");
	private static final UUID ROLE_ID_2 = UUID.fromString("55555555-5555-5555-5555-555555555555");

	private static final String APPLICATION = "CRM 360";
	private static final String PERMISSION = "CUSTOMER.CREATE";
	private static final String TENANT_RESIDENTIAL = "RESIDENTIAL";
	private static final String TENANT_SMB = "SMB";

	@Mock
	private PrincipalRepository principalRepository;

	@Mock
	private PrincipalGroupMappingRepository principalGroupMappingRepository;

	@Mock
	private GroupRoleMappingRepository groupRoleMappingRepository;

	@Mock
	private RoleApplicationPermissionRepository roleApplicationPermissionRepository;

	@Mock
	private GroupTenantAssignmentRepository groupTenantAssignmentRepository;

	private AuthorizationService authorizationService;

	@BeforeEach
	void setUp() {
		authorizationService = new AuthorizationService(
				principalRepository,
				principalGroupMappingRepository,
				groupRoleMappingRepository,
				roleApplicationPermissionRepository,
				groupTenantAssignmentRepository,
				Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
	}

	@Test
	void isAuthorized_returnsTrueForActivePrincipalWithMatchingPermission() {
		stubActivePrincipal();
		when(principalGroupMappingRepository.findActiveGroupIdsByPrincipalId(PRINCIPAL_ID))
				.thenReturn(List.of(GROUP_ID_1));
		when(groupRoleMappingRepository.findActiveRoleIdsByGroupIds(List.of(GROUP_ID_1)))
				.thenReturn(List.of(ROLE_ID_1));
		when(roleApplicationPermissionRepository.existsActivePermissionForRoles(
				List.of(ROLE_ID_1), APPLICATION, PERMISSION))
				.thenReturn(true);

		boolean authorized = authorizationService.isAuthorized(
				serviceJwt(ISSUER, SUBJECT),
				APPLICATION,
				PERMISSION,
				null);

		assertThat(authorized).isTrue();
	}

	@Test
	void resolvePrincipal_throwsWhenPrincipalNotFound() {
		when(principalRepository.findByIssuerAndExternalId(ISSUER, SUBJECT))
				.thenReturn(Optional.empty());

		assertThatThrownBy(() -> authorizationService.resolvePrincipal(serviceJwt(ISSUER, SUBJECT)))
				.isInstanceOf(AuthorizationException.class)
				.satisfies(ex -> assertThat(((AuthorizationException) ex).getReason())
						.isEqualTo(AuthorizationException.Reason.PRINCIPAL_NOT_FOUND));
	}

	@Test
	void resolvePrincipal_rejectsInactivePrincipal() {
		Principal inactivePrincipal = principal(PrincipalType.USER, "INACTIVE");
		when(principalRepository.findByIssuerAndExternalId(ISSUER, SUBJECT))
				.thenReturn(Optional.of(inactivePrincipal));

		assertThatThrownBy(() -> authorizationService.resolvePrincipal(serviceJwt(ISSUER, SUBJECT)))
				.isInstanceOf(AuthorizationException.class)
				.satisfies(ex -> assertThat(((AuthorizationException) ex).getReason())
						.isEqualTo(AuthorizationException.Reason.PRINCIPAL_INACTIVE));
	}

	@Test
	void isAuthorized_returnsFalseWhenPrincipalHasNoGroups() {
		stubActivePrincipal();
		when(principalGroupMappingRepository.findActiveGroupIdsByPrincipalId(PRINCIPAL_ID))
				.thenReturn(Collections.emptyList());

		boolean authorized = authorizationService.isAuthorized(
				serviceJwt(ISSUER, SUBJECT),
				APPLICATION,
				PERMISSION,
				null);

		assertThat(authorized).isFalse();
	}

	@Test
	void isAuthorized_returnsFalseWhenGroupsHaveNoRoles() {
		stubActivePrincipal();
		when(principalGroupMappingRepository.findActiveGroupIdsByPrincipalId(PRINCIPAL_ID))
				.thenReturn(List.of(GROUP_ID_1));
		when(groupRoleMappingRepository.findActiveRoleIdsByGroupIds(List.of(GROUP_ID_1)))
				.thenReturn(Collections.emptyList());

		boolean authorized = authorizationService.isAuthorized(
				serviceJwt(ISSUER, SUBJECT),
				APPLICATION,
				PERMISSION,
				null);

		assertThat(authorized).isFalse();
	}

	@Test
	void isAuthorized_returnsFalseWhenRoleHasNoMatchingPermission() {
		stubActivePrincipal();
		stubPermissionTraversal(List.of(GROUP_ID_1), List.of(ROLE_ID_1));
		when(roleApplicationPermissionRepository.existsActivePermissionForRoles(
				List.of(ROLE_ID_1), APPLICATION, PERMISSION))
				.thenReturn(false);

		boolean authorized = authorizationService.isAuthorized(
				serviceJwt(ISSUER, SUBJECT),
				APPLICATION,
				PERMISSION,
				null);

		assertThat(authorized).isFalse();
	}

	@Test
	void isAuthorized_returnsFalseWhenPermissionMatchesButApplicationDoesNot() {
		stubActivePrincipal();
		stubPermissionTraversal(List.of(GROUP_ID_1), List.of(ROLE_ID_1));
		when(roleApplicationPermissionRepository.existsActivePermissionForRoles(
				List.of(ROLE_ID_1), "Other Application", PERMISSION))
				.thenReturn(false);

		boolean authorized = authorizationService.isAuthorized(
				serviceJwt(ISSUER, SUBJECT),
				"Other Application",
				PERMISSION,
				null);

		assertThat(authorized).isFalse();
	}

	@Test
	void isAuthorized_returnsTrueWhenPermissionAndTenantMatch() {
		stubActivePrincipal();
		stubPermissionTraversal(List.of(GROUP_ID_1), List.of(ROLE_ID_1));
		when(roleApplicationPermissionRepository.existsActivePermissionForRoles(
				List.of(ROLE_ID_1), APPLICATION, PERMISSION))
				.thenReturn(true);
		when(groupTenantAssignmentRepository.findByGroupIdsAndTenantIdentifier(
				List.of(GROUP_ID_1), TENANT_RESIDENTIAL))
				.thenReturn(List.of(activeTenantAssignment(GROUP_ID_1, TENANT_RESIDENTIAL, null, null)));

		boolean authorized = authorizationService.isAuthorized(
				serviceJwt(ISSUER, SUBJECT),
				APPLICATION,
				PERMISSION,
				TENANT_RESIDENTIAL);

		assertThat(authorized).isTrue();
	}

	@Test
	void isAuthorized_returnsFalseWhenPermissionMatchesButTenantDoesNot() {
		stubActivePrincipal();
		stubPermissionTraversal(List.of(GROUP_ID_1), List.of(ROLE_ID_1));
		when(roleApplicationPermissionRepository.existsActivePermissionForRoles(
				List.of(ROLE_ID_1), APPLICATION, PERMISSION))
				.thenReturn(true);
		when(groupTenantAssignmentRepository.findByGroupIdsAndTenantIdentifier(
				List.of(GROUP_ID_1), TENANT_SMB))
				.thenReturn(Collections.emptyList());

		boolean authorized = authorizationService.isAuthorized(
				serviceJwt(ISSUER, SUBJECT),
				APPLICATION,
				PERMISSION,
				TENANT_SMB);

		assertThat(authorized).isFalse();
	}

	@Test
	void isAuthorized_considersPermissionFromAnyRoleOnGroup() {
		stubActivePrincipal();
		stubPermissionTraversal(List.of(GROUP_ID_1), List.of(ROLE_ID_1, ROLE_ID_2));
		when(roleApplicationPermissionRepository.existsActivePermissionForRoles(
				List.of(ROLE_ID_1, ROLE_ID_2), APPLICATION, PERMISSION))
				.thenReturn(true);

		boolean authorized = authorizationService.isAuthorized(
				serviceJwt(ISSUER, SUBJECT),
				APPLICATION,
				PERMISSION,
				null);

		assertThat(authorized).isTrue();
		verify(roleApplicationPermissionRepository).existsActivePermissionForRoles(
				eq(List.of(ROLE_ID_1, ROLE_ID_2)),
				eq(APPLICATION),
				eq(PERMISSION));
	}

	@Test
	void isAuthorized_considersPermissionsFromAllPrincipalGroups() {
		stubActivePrincipal();
		when(principalGroupMappingRepository.findActiveGroupIdsByPrincipalId(PRINCIPAL_ID))
				.thenReturn(List.of(GROUP_ID_1, GROUP_ID_2));
		when(groupRoleMappingRepository.findActiveRoleIdsByGroupIds(List.of(GROUP_ID_1, GROUP_ID_2)))
				.thenReturn(List.of(ROLE_ID_1));
		when(roleApplicationPermissionRepository.existsActivePermissionForRoles(
				List.of(ROLE_ID_1), APPLICATION, PERMISSION))
				.thenReturn(true);

		boolean authorized = authorizationService.isAuthorized(
				serviceJwt(ISSUER, SUBJECT),
				APPLICATION,
				PERMISSION,
				null);

		assertThat(authorized).isTrue();
		verify(principalGroupMappingRepository).findActiveGroupIdsByPrincipalId(PRINCIPAL_ID);
		verify(groupRoleMappingRepository).findActiveRoleIdsByGroupIds(List.of(GROUP_ID_1, GROUP_ID_2));
	}

	@Test
	void isAuthorized_returnsFalseWhenTenantAssignmentIsExpired() {
		stubActivePrincipal();
		stubPermissionTraversal(List.of(GROUP_ID_1), List.of(ROLE_ID_1));
		when(roleApplicationPermissionRepository.existsActivePermissionForRoles(
				List.of(ROLE_ID_1), APPLICATION, PERMISSION))
				.thenReturn(true);
		Instant expiredEndDate = FIXED_NOW.minusSeconds(60);
		when(groupTenantAssignmentRepository.findByGroupIdsAndTenantIdentifier(
				List.of(GROUP_ID_1), TENANT_RESIDENTIAL))
				.thenReturn(List.of(activeTenantAssignment(
						GROUP_ID_1,
						TENANT_RESIDENTIAL,
						FIXED_NOW.minusSeconds(3600),
						expiredEndDate)));

		boolean authorized = authorizationService.isAuthorized(
				serviceJwt(ISSUER, SUBJECT),
				APPLICATION,
				PERMISSION,
				TENANT_RESIDENTIAL);

		assertThat(authorized).isFalse();
	}

	@Test
	void isAuthorized_returnsFalseWhenTenantAssignmentIsInFuture() {
		stubActivePrincipal();
		stubPermissionTraversal(List.of(GROUP_ID_1), List.of(ROLE_ID_1));
		when(roleApplicationPermissionRepository.existsActivePermissionForRoles(
				List.of(ROLE_ID_1), APPLICATION, PERMISSION))
				.thenReturn(true);
		Instant futureStartDate = FIXED_NOW.plusSeconds(3600);
		when(groupTenantAssignmentRepository.findByGroupIdsAndTenantIdentifier(
				List.of(GROUP_ID_1), TENANT_RESIDENTIAL))
				.thenReturn(List.of(activeTenantAssignment(
						GROUP_ID_1,
						TENANT_RESIDENTIAL,
						futureStartDate,
						null)));

		boolean authorized = authorizationService.isAuthorized(
				serviceJwt(ISSUER, SUBJECT),
				APPLICATION,
				PERMISSION,
				TENANT_RESIDENTIAL);

		assertThat(authorized).isFalse();
	}

	@Test
	void isAuthorized_followsSamePathForServicePrincipal() {
		Principal servicePrincipal = principal(PrincipalType.SERVICE, "ACTIVE");
		when(principalRepository.findByIssuerAndExternalId(ISSUER, SUBJECT))
				.thenReturn(Optional.of(servicePrincipal));
		when(principalGroupMappingRepository.findActiveGroupIdsByPrincipalId(PRINCIPAL_ID))
				.thenReturn(List.of(GROUP_ID_1));
		when(groupRoleMappingRepository.findActiveRoleIdsByGroupIds(List.of(GROUP_ID_1)))
				.thenReturn(List.of(ROLE_ID_1));
		when(roleApplicationPermissionRepository.existsActivePermissionForRoles(
				List.of(ROLE_ID_1), APPLICATION, PERMISSION))
				.thenReturn(true);

		boolean authorized = authorizationService.isAuthorized(
				serviceJwt(ISSUER, SUBJECT),
				APPLICATION,
				PERMISSION,
				null);

		assertThat(authorized).isTrue();
		assertThat(servicePrincipal.getPrincipalType()).isEqualTo(PrincipalType.SERVICE);
	}

	@Test
	void isAuthorized_supportsFeatureResourcePermission() {
		stubActivePrincipal();
		stubPermissionTraversal(List.of(GROUP_ID_1), List.of(ROLE_ID_1));
		String featurePermission = "CUSTOMER.ACCESS";
		when(roleApplicationPermissionRepository.existsActivePermissionForRoles(
				List.of(ROLE_ID_1), APPLICATION, featurePermission))
				.thenReturn(true);

		boolean authorized = authorizationService.isAuthorized(
				serviceJwt(ISSUER, SUBJECT),
				APPLICATION,
				featurePermission,
				null);

		assertThat(authorized).isTrue();
	}

	@Test
	void isAuthorized_supportsApiResourcePermission() {
		stubActivePrincipal();
		stubPermissionTraversal(List.of(GROUP_ID_1), List.of(ROLE_ID_1));
		when(roleApplicationPermissionRepository.existsActivePermissionForRoles(
				List.of(ROLE_ID_1), APPLICATION, PERMISSION))
				.thenReturn(true);

		boolean authorized = authorizationService.isAuthorized(
				serviceJwt(ISSUER, SUBJECT),
				APPLICATION,
				PERMISSION,
				null);

		assertThat(authorized).isTrue();
		verify(roleApplicationPermissionRepository).existsActivePermissionForRoles(
				List.of(ROLE_ID_1), APPLICATION, PERMISSION);
	}

	@Test
	void resolvePrincipal_returnsPrincipalForValidServiceJwt() {
		Principal principal = principal(PrincipalType.SERVICE, "ACTIVE");
		when(principalRepository.findByIssuerAndExternalId(ISSUER, SUBJECT))
				.thenReturn(Optional.of(principal));

		Principal resolved = authorizationService.resolvePrincipal(serviceJwt(ISSUER, SUBJECT));

		assertThat(resolved).isEqualTo(principal);
		assertThat(resolved.getPrincipalName()).isEqualTo("etp-platform");
		verify(principalRepository).findByIssuerAndExternalId(ISSUER, SUBJECT);
	}

	@Test
	void resolvePrincipal_rejectsMissingIssuer() {
		assertThatThrownBy(() -> authorizationService.resolvePrincipal(serviceJwt(null, SUBJECT)))
				.isInstanceOf(AuthorizationException.class)
				.satisfies(ex -> assertThat(((AuthorizationException) ex).getReason())
						.isEqualTo(AuthorizationException.Reason.INVALID_IDENTITY));

		assertThatThrownBy(() -> authorizationService.resolvePrincipal(serviceJwt("", SUBJECT)))
				.isInstanceOf(AuthorizationException.class)
				.satisfies(ex -> assertThat(((AuthorizationException) ex).getReason())
						.isEqualTo(AuthorizationException.Reason.INVALID_IDENTITY));
	}

	@Test
	void resolvePrincipal_rejectsMissingSubject() {
		assertThatThrownBy(() -> authorizationService.resolvePrincipal(serviceJwt(ISSUER, null)))
				.isInstanceOf(AuthorizationException.class)
				.satisfies(ex -> assertThat(((AuthorizationException) ex).getReason())
						.isEqualTo(AuthorizationException.Reason.INVALID_IDENTITY));

		assertThatThrownBy(() -> authorizationService.resolvePrincipal(serviceJwt(ISSUER, "")))
				.isInstanceOf(AuthorizationException.class)
				.satisfies(ex -> assertThat(((AuthorizationException) ex).getReason())
						.isEqualTo(AuthorizationException.Reason.INVALID_IDENTITY));
	}

	private void stubActivePrincipal() {
		when(principalRepository.findByIssuerAndExternalId(ISSUER, SUBJECT))
				.thenReturn(Optional.of(principal(PrincipalType.USER, "ACTIVE")));
	}

	private void stubPermissionTraversal(List<UUID> groupIds, List<UUID> roleIds) {
		when(principalGroupMappingRepository.findActiveGroupIdsByPrincipalId(PRINCIPAL_ID))
				.thenReturn(groupIds);
		when(groupRoleMappingRepository.findActiveRoleIdsByGroupIds(groupIds))
				.thenReturn(roleIds);
	}

	private static Principal principal(PrincipalType type, String status) {
		Principal principal = new Principal(type, ISSUER, SUBJECT, "etp-platform", status);
		ReflectionTestUtils.setField(principal, "principalId", PRINCIPAL_ID);
		return principal;
	}

	private static GroupTenantAssignment activeTenantAssignment(
			UUID groupId,
			String tenantCode,
			Instant startDate,
			Instant endDate) {
		Group group = BeanUtils.instantiateClass(Group.class);
		ReflectionTestUtils.setField(group, "groupId", groupId);
		ReflectionTestUtils.setField(group, "groupType", GroupType.HUMAN);
		ReflectionTestUtils.setField(group, "groupName", "Sales RESI");
		ReflectionTestUtils.setField(group, "status", "ACTIVE");

		Tenant tenant = BeanUtils.instantiateClass(Tenant.class);
		ReflectionTestUtils.setField(tenant, "tenantId", UUID.randomUUID());
		ReflectionTestUtils.setField(tenant, "tenantCode", tenantCode);
		ReflectionTestUtils.setField(tenant, "tenantName", tenantCode);
		ReflectionTestUtils.setField(tenant, "status", "ACTIVE");

		Scope scope = BeanUtils.instantiateClass(Scope.class);
		ReflectionTestUtils.setField(scope, "scopeId", UUID.randomUUID());
		ReflectionTestUtils.setField(scope, "scopeName", "Tenant Scope");
		ReflectionTestUtils.setField(scope, "scopeType", "TENANT");
		ReflectionTestUtils.setField(scope, "scopeValue", tenantCode);
		ReflectionTestUtils.setField(scope, "status", "ACTIVE");

		GroupTenantAssignment assignment = BeanUtils.instantiateClass(GroupTenantAssignment.class);
		ReflectionTestUtils.setField(assignment, "group", group);
		ReflectionTestUtils.setField(assignment, "tenant", tenant);
		ReflectionTestUtils.setField(assignment, "scope", scope);
		ReflectionTestUtils.setField(assignment, "status", "ACTIVE");
		ReflectionTestUtils.setField(assignment, "startDate", startDate);
		ReflectionTestUtils.setField(assignment, "endDate", endDate);
		return assignment;
	}

	private static Jwt serviceJwt(String issuer, String subject) {
		Jwt.Builder builder = Jwt.withTokenValue("test-token")
				.header("alg", "none");

		if (issuer != null) {
			builder.issuer(issuer);
		}
		if (subject != null) {
			builder.subject(subject);
		}

		return builder.build();
	}
}
