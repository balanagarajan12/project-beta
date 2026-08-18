import type { AuthSession } from './types';

/** Single storage key for persisted auth sessions (passwords are never stored). */
export const AUTH_SESSION_STORAGE_KEY = 'etp.auth.session';

function isValidSessionShape(value: unknown): value is AuthSession {
  if (!value || typeof value !== 'object') {
    return false;
  }

  const session = value as AuthSession;

  return (
    typeof session.accessToken === 'string' &&
    typeof session.expiresAt === 'string' &&
    typeof session.rememberMe === 'boolean' &&
    session.user !== null &&
    typeof session.user === 'object' &&
    typeof session.user.id === 'string' &&
    typeof session.user.username === 'string' &&
    typeof session.user.displayName === 'string' &&
    typeof session.user.email === 'string' &&
    Array.isArray(session.user.roles) &&
    session.user.roles.every((role) => typeof role === 'string')
  );
}

export function isSessionExpired(session: AuthSession): boolean {
  const expiresAt = Date.parse(session.expiresAt);
  if (Number.isNaN(expiresAt)) {
    return true;
  }
  return expiresAt <= Date.now();
}

export function saveSession(session: AuthSession): void {
  localStorage.removeItem(AUTH_SESSION_STORAGE_KEY);
  sessionStorage.removeItem(AUTH_SESSION_STORAGE_KEY);

  const storage = session.rememberMe ? localStorage : sessionStorage;
  storage.setItem(AUTH_SESSION_STORAGE_KEY, JSON.stringify(session));
}

export function getSession(): AuthSession | null {
  const raw =
    localStorage.getItem(AUTH_SESSION_STORAGE_KEY) ??
    sessionStorage.getItem(AUTH_SESSION_STORAGE_KEY);

  if (!raw) {
    return null;
  }

  try {
    const parsed: unknown = JSON.parse(raw);
    if (!isValidSessionShape(parsed)) {
      clearSession();
      return null;
    }
    return parsed;
  } catch {
    clearSession();
    return null;
  }
}

export function clearSession(): void {
  localStorage.removeItem(AUTH_SESSION_STORAGE_KEY);
  sessionStorage.removeItem(AUTH_SESSION_STORAGE_KEY);
}
