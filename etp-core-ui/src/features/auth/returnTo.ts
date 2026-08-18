/** Default post-authentication destination when no valid return URL is present. */
export const DEFAULT_AUTH_RETURN_PATH = '/dashboard';

/**
 * Validates an internal return path for post-login navigation.
 * Rejects external URLs, protocol-relative paths, and the login route.
 */
export function sanitizeReturnTo(returnTo: string | null | undefined): string {
  if (!returnTo) {
    return DEFAULT_AUTH_RETURN_PATH;
  }

  const trimmed = returnTo.trim();

  if (!trimmed.startsWith('/') || trimmed.startsWith('//')) {
    return DEFAULT_AUTH_RETURN_PATH;
  }

  if (trimmed.includes('://') || trimmed.includes('\\')) {
    return DEFAULT_AUTH_RETURN_PATH;
  }

  if (trimmed === '/login' || trimmed.startsWith('/login?') || trimmed.startsWith('/login#')) {
    return DEFAULT_AUTH_RETURN_PATH;
  }

  return trimmed;
}

export function buildLoginPathWithReturnTo(returnTo: string): string {
  const sanitized = sanitizeReturnTo(returnTo);
  return `/login?returnTo=${encodeURIComponent(sanitized)}`;
}
