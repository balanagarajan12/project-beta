const IGNORED_REALM_ROLES = new Set(['offline_access', 'uma_authorization']);

function titleCase(value: string): string {
  return value
    .split(' ')
    .filter(Boolean)
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1).toLowerCase())
    .join(' ');
}

/**
 * Derives a display role from Keycloak realm roles on the authenticated user.
 */
export function formatUserRole(roles: string[]): string {
  const meaningful = roles.filter(
    (role) => !IGNORED_REALM_ROLES.has(role) && !role.startsWith('default-roles-'),
  );

  if (meaningful.length === 0) {
    return 'ETP User';
  }

  return meaningful
    .map((role) => titleCase(role.replace(/[-_]/g, ' ')))
    .join(', ');
}
