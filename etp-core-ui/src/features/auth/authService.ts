/**
 * ETP Core UI authentication service.
 *
 * Integrates with the ETP IAM capability for username/password login.
 * Development mock authentication remains available when explicitly enabled.
 */

import { env, getIamBaseUrl } from '@/config/env';
import * as authStorage from './authStorage';
import { loginWithIam } from './iamAuthClient';
import { AuthError, type AuthSession, type LoginCredentials } from './types';

const MOCK_SESSION_TTL_MS = 8 * 60 * 60 * 1000;
const MOCK_REMEMBER_ME_TTL_MS = 7 * 24 * 60 * 60 * 1000;
const MOCK_LOGIN_DELAY_MS = 400;

export async function login(credentials: LoginCredentials): Promise<AuthSession> {
  const session = env.mockAuthEnabled
    ? await mockLogin(credentials)
    : await loginWithIam(getIamBaseUrl(), credentials);
  authStorage.saveSession(session);
  return session;
}

export function logout(): void {
  authStorage.clearSession();
}

export function getSession(): AuthSession | null {
  const session = authStorage.getSession();
  if (!session) {
    return null;
  }

  if (authStorage.isSessionExpired(session)) {
    authStorage.clearSession();
    return null;
  }

  return session;
}

export function isAuthenticated(): boolean {
  return getSession() !== null;
}

/**
 * DEVELOPMENT ONLY — not production authentication.
 * Validates credentials against VITE_MOCK_AUTH_* environment variables.
 */
async function mockLogin(credentials: LoginCredentials): Promise<AuthSession> {
  if (!env.mockAuthUsername || !env.mockAuthPassword) {
    throw new AuthError(
      'AUTH_DISABLED',
      'Development authentication credentials are not configured.',
    );
  }

  await new Promise((resolve) => window.setTimeout(resolve, MOCK_LOGIN_DELAY_MS));

  const username = credentials.username.trim();

  if (
    username !== env.mockAuthUsername ||
    credentials.password !== env.mockAuthPassword
  ) {
    throw new AuthError(
      'INVALID_CREDENTIALS',
      'Invalid email or username, or password.',
    );
  }

  const ttl = credentials.rememberMe ? MOCK_REMEMBER_ME_TTL_MS : MOCK_SESSION_TTL_MS;
  const expiresAt = new Date(Date.now() + ttl).toISOString();
  const accessToken = `etp-dev-mock-${crypto.randomUUID()}`;

  return {
    user: {
      id: 'etp-dev-mock-user-1',
      username,
      displayName: 'ETP Platform User',
      email: username.includes('@') ? username : `${username}@etp.local`,
      roles: ['platform-admin'],
    },
    accessToken,
    expiresAt,
    rememberMe: credentials.rememberMe,
  };
}
