package com.etp.iam.authorization;

import com.etp.iam.entity.GroupTenantAssignment;
import com.etp.iam.entity.Principal;
import com.etp.iam.repository.GroupRoleMappingRepository;
import com.etp.iam.repository.GroupTenantAssignmentRepository;
import com.etp.iam.repository.PrincipalGroupMappingRepository;
import com.etp.iam.repository.PrincipalRepository;
import com.etp.iam.repository.RoleApplicationPermissionRepository;
import java.net.URL;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

@Service
public class AuthorizationService {

	private static final String ACTIVE_STATUS = "ACTIVE";

	private final PrincipalRepository principalRepository;
	private final PrincipalGroupMappingRepository principalGroupMappingRepository;
	private final GroupRoleMappingRepository groupRoleMappingRepository;
	private final RoleApplicationPermissionRepository roleApplicationPermissionRepository;
	private final GroupTenantAssignmentRepository groupTenantAssignmentRepository;
	private final Clock clock;

	@Autowired
	public AuthorizationService(
			PrincipalRepository principalRepository,
			PrincipalGroupMappingRepository principalGroupMappingRepository,
			GroupRoleMappingRepository groupRoleMappingRepository,
			RoleApplicationPermissionRepository roleApplicationPermissionRepository,
			GroupTenantAssignmentRepository groupTenantAssignmentRepository) {
		this(
				principalRepository,
				principalGroupMappingRepository,
				groupRoleMappingRepository,
				roleApplicationPermissionRepository,
				groupTenantAssignmentRepository,
				Clock.systemUTC());
	}

	AuthorizationService(
			PrincipalRepository principalRepository,
			PrincipalGroupMappingRepository principalGroupMappingRepository,
			GroupRoleMappingRepository groupRoleMappingRepository,
			RoleApplicationPermissionRepository roleApplicationPermissionRepository,
			GroupTenantAssignmentRepository groupTenantAssignmentRepository,
			Clock clock) {
		this.principalRepository = principalRepository;
		this.principalGroupMappingRepository = principalGroupMappingRepository;
		this.groupRoleMappingRepository = groupRoleMappingRepository;
		this.roleApplicationPermissionRepository = roleApplicationPermissionRepository;
		this.groupTenantAssignmentRepository = groupTenantAssignmentRepository;
		this.clock = clock;
	}

	public boolean isAuthorized(Jwt jwt, String application, String permission, String tenant) {
		Principal principal = resolvePrincipal(jwt);
		List<UUID> groupIds = resolveGroupIds(principal);
		if (groupIds.isEmpty()) {
			return false;
		}

		List<UUID> roleIds = resolveRoleIds(groupIds);
		if (roleIds.isEmpty()) {
			return false;
		}

		if (!hasPermission(roleIds, application, permission)) {
			return false;
		}

		if (tenant != null && !tenant.isBlank()) {
			return hasTenantAccess(groupIds, tenant);
		}

		return true;
	}

	public Principal resolvePrincipal(Jwt jwt) {
		String issuer = extractIssuer(jwt);
		String subject = jwt.getSubject();

		if (issuer == null || issuer.isBlank()) {
			throw new AuthorizationException(
					AuthorizationException.Reason.INVALID_IDENTITY,
					"JWT issuer is missing or blank");
		}

		if (subject == null || subject.isBlank()) {
			throw new AuthorizationException(
					AuthorizationException.Reason.INVALID_IDENTITY,
					"JWT subject is missing or blank");
		}

		Principal principal = principalRepository.findByIssuerAndExternalId(issuer, subject)
				.orElseThrow(() -> new AuthorizationException(
						AuthorizationException.Reason.PRINCIPAL_NOT_FOUND,
						"No principal found for the authenticated identity"));

		if (!ACTIVE_STATUS.equals(principal.getStatus())) {
			throw new AuthorizationException(
					AuthorizationException.Reason.PRINCIPAL_INACTIVE,
					"Principal is inactive");
		}

		return principal;
	}

	private List<UUID> resolveGroupIds(Principal principal) {
		return principalGroupMappingRepository.findActiveGroupIdsByPrincipalId(principal.getPrincipalId());
	}

	private List<UUID> resolveRoleIds(List<UUID> groupIds) {
		return groupRoleMappingRepository.findActiveRoleIdsByGroupIds(groupIds);
	}

	private boolean hasPermission(List<UUID> roleIds, String application, String permission) {
		return roleApplicationPermissionRepository.existsActivePermissionForRoles(
				roleIds,
				application,
				permission);
	}

	private boolean hasTenantAccess(List<UUID> groupIds, String tenant) {
		List<GroupTenantAssignment> assignments = groupTenantAssignmentRepository
				.findByGroupIdsAndTenantIdentifier(groupIds, tenant);
		Instant now = Instant.now(clock);
		return assignments.stream().anyMatch(assignment -> isAssignmentEffective(assignment, now));
	}

	private boolean isAssignmentEffective(GroupTenantAssignment assignment, Instant now) {
		if (!ACTIVE_STATUS.equals(assignment.getStatus())) {
			return false;
		}

		if (assignment.getTenant() != null && !ACTIVE_STATUS.equals(assignment.getTenant().getStatus())) {
			return false;
		}

		if (assignment.getScope() != null && !ACTIVE_STATUS.equals(assignment.getScope().getStatus())) {
			return false;
		}

		if (assignment.getStartDate() != null && now.isBefore(assignment.getStartDate())) {
			return false;
		}

		if (assignment.getEndDate() != null && now.isAfter(assignment.getEndDate())) {
			return false;
		}

		return true;
	}

	private String extractIssuer(Jwt jwt) {
		try {
			URL issuerUrl = jwt.getIssuer();
			return issuerUrl != null ? issuerUrl.toString() : null;
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}
}
