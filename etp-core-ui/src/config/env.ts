/**
 * Frontend environment configuration.
 * Values come from Vite env vars — never hard-code API URLs in source.
 */

function requireEnv(name: string, value: string | undefined): string {
  if (value === undefined || value.trim() === '') {
    throw new Error(`Missing required environment variable: ${name}`);
  }
  return value.trim();
}

function optionalEnv(value: string | undefined, fallback: string): string {
  if (value === undefined || value.trim() === '') {
    return fallback;
  }
  return value.trim();
}

function parseBool(value: string | undefined, fallback: boolean): boolean {
  if (value === undefined || value.trim() === '') {
    return fallback;
  }
  return value.trim().toLowerCase() === 'true';
}

export const env = {
  /**
   * Base URL for ETP Platform API (no trailing slash).
   * Required at runtime when making API calls.
   */
  apiBaseUrl: optionalEnv(import.meta.env.VITE_API_BASE_URL, ''),

  /**
   * Base URL for ETP IAM capability API (no trailing slash).
   */
  iamBaseUrl: optionalEnv(import.meta.env.VITE_IAM_BASE_URL, ''),

  /**
   * Development-only mock authentication (Step 1A fallback).
   * Disabled by default when using real IAM login.
   */
  mockAuthEnabled: parseBool(import.meta.env.VITE_MOCK_AUTH_ENABLED, false),
  mockAuthUsername: optionalEnv(import.meta.env.VITE_MOCK_AUTH_USERNAME, ''),
  mockAuthPassword: optionalEnv(import.meta.env.VITE_MOCK_AUTH_PASSWORD, ''),

  isDev: import.meta.env.DEV,
  isProd: import.meta.env.PROD,
} as const;

/**
 * Returns API base URL or throws if not configured.
 * Use when initiating API requests.
 */
export function getApiBaseUrl(): string {
  return requireEnv('VITE_API_BASE_URL', import.meta.env.VITE_API_BASE_URL);
}

/**
 * Returns IAM base URL or throws if not configured.
 * Use when initiating IAM authentication requests.
 */
export function getIamBaseUrl(): string {
  return requireEnv('VITE_IAM_BASE_URL', import.meta.env.VITE_IAM_BASE_URL);
}
